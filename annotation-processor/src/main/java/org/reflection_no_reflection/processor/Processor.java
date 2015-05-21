package org.reflection_no_reflection.processor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeMirror;
import org.reflection_no_reflection.Annotation;
import org.reflection_no_reflection.Class;
import org.reflection_no_reflection.Field;
import org.reflection_no_reflection.Method;

/**
 * An annotation processor that detects classes that need to receive injections.
 * It is a {@link AbstractProcessor} that can be triggered for all kinds of annotations.
 * It will create a RNR database of annotated fields, methods and constuctors.
 *
 * @author SNI
 */
//TODO MUST REMOVE THIS. ANNOTATION TYPES SHOULD BE DYNAMIC
//getSupportedAnnotationType must be triggered
//chances are that the processor must be called in a different way by the gradle
//plugin. We have to get full control over annotation processor instance creation.
@SupportedOptions({"guiceAnnotationDatabasePackageName", "guiceUsesFragmentUtil", "guiceCommentsInjector", "annotatedClasses"})
public class Processor extends AbstractProcessor {

    private boolean isUsingFragmentUtil = true;
    private boolean isCommentingInjector = true;
    private Set<String> annotatedClasses = new HashSet<>();

    /** Contains all classes that contain injection points. */
    private HashSet<Class> annotatedClassSet = new HashSet<>();

    /** Contains all classes that can be injected into a class with injection points. */
    private HashSet<String> classesUnderAnnotation;
    /** Name of the package to generate the annotation database into. */
    private String annotationDatabasePackageName;
    private ProcessingEnvironment processingEnv;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        this.processingEnv = processingEnv;
        annotationDatabasePackageName = processingEnv.getOptions().get("guiceAnnotationDatabasePackageName");
        classesUnderAnnotation = new HashSet<>();
        String isUsingFragmentUtilString = processingEnv.getOptions().get("guiceUsesFragmentUtil");
        if (isUsingFragmentUtilString != null) {
            isUsingFragmentUtil = Boolean.parseBoolean(isUsingFragmentUtilString);
        }
        String isCommentingInjectorString = processingEnv.getOptions().get("guiceCommentsInjector");
        if (isCommentingInjectorString != null) {
            isCommentingInjector = Boolean.parseBoolean(isCommentingInjectorString);
        }
        String annotatedClassesString = processingEnv.getOptions().get("annotatedClasses");
        if (annotatedClassesString != null) {
            annotatedClasses.addAll(Arrays.asList(annotatedClassesString.split(",")));
        }
        Class.purgeAllClasses();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Not sure why, but sometimes we're getting called with an empty list of annotations.
        if (annotations.isEmpty()) {
            return true;
        }

        for (TypeElement annotation : annotations) {
            String annotationClassName = getTypeName(annotation);

            for (Element injectionPoint : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (injectionPoint.getEnclosingElement() instanceof TypeElement && injectionPoint instanceof VariableElement) {
                    addFieldToAnnotationDatabase(annotationClassName, injectionPoint);
                } else if (injectionPoint.getEnclosingElement() instanceof ExecutableElement && injectionPoint instanceof VariableElement) {
                    addParameterToAnnotationDatabase(annotationClassName, injectionPoint);
                } else if (injectionPoint instanceof ExecutableElement) {
                    addMethodOrConstructorToAnnotationDatabase((ExecutableElement) injectionPoint);
                } else if (injectionPoint instanceof TypeElement) {
                    addClassToAnnotationDatabase(injectionPoint);
                }
            }
        }

        return true;
    }

    private void addClassToAnnotationDatabase(Element injectionPoint) {
        TypeElement typeElementRequiringScanning = (TypeElement) injectionPoint;
        String typeElementName = getTypeName(typeElementRequiringScanning);
        //System.out.printf("Type: %s, is injected\n",typeElementName);
        annotatedClassSet.add(new Class(typeElementName));
    }

    private void addFieldToAnnotationDatabase(String annotationClassName, Element injectionPoint) {
        String injectionPointName;
        String injectedClassName = getTypeName(injectionPoint);
        if (isPrimitiveType(injectedClassName)) {
            classesUnderAnnotation.add(injectedClassName + ".class");
        } else {
            classesUnderAnnotation.add(injectedClassName);
        }

        //System.out.printf("Type: %s, injection: %s \n",typeElementName, injectionPointName);
        addToInjectedFields(injectionPoint.getModifiers(), injectedClassName, injectionPoint);
    }

    private boolean isPrimitiveType(String injectedClassName) {
        switch (injectedClassName) {
            case "byte":
            case "short":
            case "int":
            case "long":
            case "float":
            case "double":
            case "boolean":
            case "char":
                return true;
        }
        return false;
    }

    private void addParameterToAnnotationDatabase(String annotationClassName, Element paramElement) {
        Element enclosing = paramElement.getEnclosingElement();
        String injectionPointName = enclosing.getSimpleName().toString();
        //System.out.printf("Type: %s, injection: %s \n",typeElementName, injectionPointName);
        if (injectionPointName.startsWith("<init>")) {
            //TODO add constructor
        } else {
            addMethod((ExecutableElement) paramElement.getEnclosingElement());
        }
    }

    private Class[] getParameterTypes(ExecutableElement methodElement) {
        final List<? extends VariableElement> parameters = methodElement.getParameters();
        Class[] paramTypes = new Class[parameters.size()];
        for (int indexParam = 0; indexParam < parameters.size(); indexParam++) {
            VariableElement parameter = parameters.get(indexParam);
            paramTypes[indexParam] = getClass(getTypeName(parameter));
        }
        return paramTypes;
    }

    private Class[] getExceptionTypes(ExecutableElement methodElement) {
        final List<? extends TypeMirror> exceptionTypes = methodElement.getThrownTypes();
        Class[] paramTypes = new Class[exceptionTypes.size()];
        for (int indexParam = 0; indexParam < exceptionTypes.size(); indexParam++) {
            TypeMirror exceptionType = exceptionTypes.get(indexParam);
            paramTypes[indexParam] = getClass(exceptionType.toString());
        }
        return paramTypes;
    }


    private void addMethodOrConstructorToAnnotationDatabase(ExecutableElement methodElement) {
        String injectionPointName = methodElement.getSimpleName().toString();
        //System.out.printf("Type: %s, injection: %s \n",typeElementName, injectionPointName);
        if (injectionPointName.startsWith("<init>")) {
            //TODO add constructor
        } else {
            addMethod(methodElement);
        }
    }

    protected void addToInjectedFields(Set<Modifier> modifiers, String injectedClassName, Element fieldElement) {
        String injectionPointName = fieldElement.getSimpleName().toString();
        TypeElement declaringClassElement = (TypeElement) fieldElement.getEnclosingElement();
        String declaringClassName = getTypeName(declaringClassElement);
        final List<Annotation> annotations = extractAnnotations(fieldElement);
        int modifiersInt = convertModifiersFromAnnnotationProcessing(modifiers);
        final Field field = new Field(injectionPointName, getClass(injectedClassName), getClass(declaringClassName), modifiersInt, annotations);

        //rnr 2
        final Class<?> classContainingField = getClass(declaringClassName);
        classContainingField.addField(field);
        annotatedClassSet.add(classContainingField);
    }

    private void addMethod(ExecutableElement methodElement) {
        final Element enclosing = methodElement.getEnclosingElement();
        final String methodName = methodElement.getSimpleName().toString();
        final TypeElement declaringClassElement = (TypeElement) enclosing;
        final String declaringClassName = getTypeName(declaringClassElement);
        final Class[] paramTypes = getParameterTypes(methodElement);
        final Class[] exceptionTypes = getExceptionTypes(methodElement);
        final String returnTypeName = methodElement.getReturnType().toString();
        final Method method = new Method(getClass(declaringClassName),
                                         methodName,
                                         paramTypes,
                                         getClass(returnTypeName),
                                         exceptionTypes,
                                         convertModifiersFromAnnnotationProcessing(methodElement.getModifiers()));

        final List<Annotation> annotations = extractAnnotations(methodElement);

        method.setDeclaredAnnotations(annotations);

        final Class<?> classContainingMethod = getClass(declaringClassName);
        classContainingMethod.addMethod(method);
        annotatedClassSet.add(classContainingMethod);
    }

    private List<Annotation> extractAnnotations(Element methodElement) {
        final List<Annotation> annotations = new ArrayList<>();
        for (AnnotationMirror annotationMirror : methodElement.getAnnotationMirrors()) {
            final Map<Method, Object> mapMethodToValue = new HashMap<>();
            final String annotationType = annotationMirror.getAnnotationType().toString();
            for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : annotationMirror.getElementValues().entrySet()) {
                final String methodOfAnnotationName = entry.getKey().getSimpleName().toString();

                //RnR 2
                final Method methodOfAnnotation = new Method(getClass(annotationType),
                                                             methodOfAnnotationName,
                                                             //TODO : param types
                                                             new Class[0],
                                                             getClass(entry.getKey().getReturnType().toString()),
                                                             //TODO : exception types
                                                             new Class[0],
                                                             java.lang.reflect.Modifier.PUBLIC
                );
                mapMethodToValue.put(methodOfAnnotation, entry.getValue().getValue());
            }

            final Annotation annotation = new Annotation(getClass(annotationType), mapMethodToValue);
            annotations.add(annotation);
        }
        return annotations;
    }

    private Class getClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return new Class(name);
        }
    }

    private int convertModifiersFromAnnnotationProcessing(Set<Modifier> modifiers) {
        int result = 0;
        for (Modifier modifier : modifiers) {
            switch (modifier) {
                case ABSTRACT:
                    result |= java.lang.reflect.Modifier.ABSTRACT;
                    break;
                case PUBLIC:
                    result |= java.lang.reflect.Modifier.PUBLIC;
                    break;
                case PRIVATE:
                    result |= java.lang.reflect.Modifier.PRIVATE;
                    break;
                case STATIC:
                    result |= java.lang.reflect.Modifier.STATIC;
                    break;
                case PROTECTED:
                    result |= java.lang.reflect.Modifier.PROTECTED;
                    break;
                default:
            }
        }
        return result;
    }

    private String getTypeName(TypeElement typeElementRequiringScanning) {
        if (typeElementRequiringScanning.getEnclosingElement() instanceof TypeElement) {
            return getTypeName(typeElementRequiringScanning.getEnclosingElement()) + "$" + typeElementRequiringScanning.getSimpleName().toString();
        } else {
            return typeElementRequiringScanning.getQualifiedName().toString();
        }
    }

    private String getTypeName(Element injectionPoint) {
        String injectedClassName = null;
        final TypeMirror fieldTypeMirror = injectionPoint.asType();
        if (fieldTypeMirror instanceof DeclaredType) {
            injectedClassName = getTypeName((TypeElement) ((DeclaredType) fieldTypeMirror).asElement());
        } else if (fieldTypeMirror instanceof PrimitiveType) {
            injectedClassName = fieldTypeMirror.toString();
        }
        return injectedClassName;
    }

    private void addToInjectedMembers(String annotationClassName, String typeElementName, String injectionPointName, HashMap<String, Map<String, Set<String>>> mapAnnotationToMapClassWithInjectionNameToMembersSet) {
        Map<String, Set<String>> mapClassWithInjectionNameToMemberSet = mapAnnotationToMapClassWithInjectionNameToMembersSet.get(annotationClassName);
        if (mapClassWithInjectionNameToMemberSet == null) {
            mapClassWithInjectionNameToMemberSet = new HashMap<>();
            mapAnnotationToMapClassWithInjectionNameToMembersSet.put(annotationClassName, mapClassWithInjectionNameToMemberSet);
        }

        Set<String> injectionPointNameSet = mapClassWithInjectionNameToMemberSet.get(typeElementName);
        if (injectionPointNameSet == null) {
            injectionPointNameSet = new HashSet<>();
            mapClassWithInjectionNameToMemberSet.put(typeElementName, injectionPointNameSet);
        }
        injectionPointNameSet.add(injectionPointName);
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        //http://stackoverflow.com/a/8188860/693752
        return SourceVersion.latest();
    }

    @Override public Set<String> getSupportedAnnotationTypes() {
        return annotatedClasses;
    }

    public void setAnnotatedClasses(Set<String> annotatedClasses) {
        this.annotatedClasses = annotatedClasses;
    }

    public Set<Class> getAnnotatedClasses() {
        return annotatedClassSet;
    }
}
