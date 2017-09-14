package org.particleframework.annotation.processing;

import org.particleframework.config.ConfigurationProperties;
import org.particleframework.context.annotation.*;
import org.particleframework.core.io.service.ServiceDescriptorGenerator;
import org.particleframework.inject.BeanDefinitionClass;
import org.particleframework.inject.annotation.Executable;
import org.particleframework.inject.writer.BeanDefinitionClassWriter;
import org.particleframework.inject.writer.BeanDefinitionWriter;
import org.particleframework.inject.writer.ClassWriterOutputVisitor;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Scope;
import javax.inject.Singleton;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.ElementScanner8;
import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.stream.Collectors;

import static javax.lang.model.element.ElementKind.*;
import static javax.lang.model.element.Modifier.PUBLIC;
import static javax.lang.model.type.TypeKind.ARRAY;
import static javax.lang.model.type.TypeKind.DECLARED;

@SupportedAnnotationTypes({
    "javax.annotation.PostConstruct",
    "javax.annotation.PreDestroy",
    "javax.inject.Inject",
    "javax.inject.Qualifier",
    "javax.inject.Singleton",
    "org.particleframework.config.ConfigurationProperties",
    "org.particleframework.context.annotation.Bean",
    "org.particleframework.context.annotation.Context",
    "org.particleframework.context.annotation.Factory",
    "org.particleframework.context.annotation.Replaces",
    "org.particleframework.context.annotation.Value",
    "org.particleframework.inject.annotation.Executable",
    "org.particleframework.inject.qualifiers.primary.Primary",
    "org.particleframework.stereotype.Controller"
})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class BeanDefinitionInjectProcessor extends AbstractInjectAnnotationProcessor {

    private Map<String, AnnBeanElementVisitor> beanDefinitionWriters;
    private ServiceDescriptorGenerator serviceDescriptorGenerator;
    private ClassWriterOutputVisitor classWriterOutputVisitor;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.serviceDescriptorGenerator = new ServiceDescriptorGenerator();
        this.beanDefinitionWriters = new LinkedHashMap<>();
        this.classWriterOutputVisitor = new BeanDefinitionWriterVisitor(filer, targetDirectory);
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {

        // accumulate all the class elements for all annotated elements
        annotations.forEach(annotation -> {
            roundEnv.getElementsAnnotatedWith(annotation)
                .stream()
                // filtering annotation definitions, which are not processed
                .filter(element -> element.getKind() != ANNOTATION_TYPE)
                .forEach(element -> {
                    TypeElement typeElement = modelUtils.classElementFor(element);
                    AnnBeanElementVisitor visitor = new AnnBeanElementVisitor(typeElement);
                    beanDefinitionWriters.put(typeElement.getQualifiedName().toString(), visitor);
                });
        });

        // process the annotations
        if (roundEnv.processingOver()) {
            note("Creating bean classes for %s type elements", beanDefinitionWriters.size());
            beanDefinitionWriters.values().forEach(visitor -> {
                TypeElement classElement = visitor.getConcreteClass();
                classElement.accept(visitor, classElement.getQualifiedName().toString());
                visitor.getBeanDefinitionWriters().values().forEach(writer -> processBeanDefinitions(classElement, writer));
            });
        }
        return true;
    }

    private void processBeanDefinitions(TypeElement beanClassElement, BeanDefinitionWriter beanDefinitionWriter) {
        try {
            beanDefinitionWriter.visitBeanDefinitionEnd();
            beanDefinitionWriter.accept(classWriterOutputVisitor);

            String beanDefinitionName = beanDefinitionWriter.getBeanDefinitionName();
            String beanTypeName = beanDefinitionWriter.getBeanTypeName();

            BeanDefinitionClassWriter beanDefinitionClassWriter =
                new BeanDefinitionClassWriter(beanTypeName, beanDefinitionName);
            String className = beanDefinitionClassWriter.getBeanDefinitionQualifiedClassName();
            beanDefinitionClassWriter.setContextScope(
                annotationUtils.hasStereotype(beanClassElement, Context.class));
            AnnotationMirror replacesAnn =
                annotationUtils.findAnnotationWithStereotype(beanClassElement, Replaces.class);
            if (replacesAnn != null) {
                annotationUtils.getAnnotationElementValue("value", replacesAnn)
                    .ifPresent(beanDefinitionClassWriter::setReplaceBeanName);
            }

            JavaFileObject beanDefClassFileObject = filer.createClassFile(className);
            try (OutputStream out = beanDefClassFileObject.openOutputStream()) {
                beanDefinitionClassWriter.writeTo(out);
            }
            serviceDescriptorGenerator.generate(
                targetDirectory,
                beanDefinitionClassWriter.getBeanDefinitionClassName(),
                BeanDefinitionClass.class);
        } catch (IOException e) {
            // raise a compile error
            error("Unexpected error: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    class AnnBeanElementVisitor extends ElementScanner8<Object,Object> {
        private final TypeElement concreteClass;
        private final Map<Element, BeanDefinitionWriter> beanDefinitionWriters;
        private final boolean isConfigurationPropertiesType;
        private final boolean isFactoryType;
        private final boolean isExecutableType;

        AnnBeanElementVisitor(TypeElement concreteClass) {
            this.concreteClass = concreteClass;
            beanDefinitionWriters = new LinkedHashMap<>();
            this.isFactoryType = annotationUtils.hasStereotype(concreteClass, Factory.class);
            this.isExecutableType = annotationUtils.hasStereotype(concreteClass, Executable.class);
            this.isConfigurationPropertiesType = annotationUtils.hasStereotype(concreteClass, ConfigurationProperties.class);
        }

        TypeElement getConcreteClass() {
            return concreteClass;
        }

        Map<Element, BeanDefinitionWriter> getBeanDefinitionWriters() {
            return beanDefinitionWriters;
        }

        @Override
        public Object visitType(TypeElement classElement, Object o) {
            assert (classElement.getKind() == CLASS) : "classElement must be a class";

            Element enclosingElement = classElement.getEnclosingElement();
            // don't process inner class unless this is the visitor for it
            if (!enclosingElement.getKind().isClass() ||
                concreteClass.getQualifiedName().equals(classElement.getQualifiedName())) {

                if (concreteClass.getQualifiedName().equals(classElement.getQualifiedName())) {
                    // we know this class has supported annotations so we need a beandef writer for it
                    BeanDefinitionWriter beanDefinitionWriter = createBeanDefinitionWriterFor(classElement);
                    beanDefinitionWriters.put(concreteClass, beanDefinitionWriter);

                    ExecutableElement ctor = publicConstructorFor(classElement);
                    ExecutableElementParamInfo paramInfo = populateParameterData(ctor);
                    beanDefinitionWriter.visitBeanDefinitionConstructor(
                        paramInfo.getParameters(),
                        paramInfo.getQualifierTypes(),
                        paramInfo.getGenericTypes());
                }

                List<? extends Element> elements = classElement.getEnclosedElements().stream()
                    // already handled the public ctor
                    .filter(element -> element.getKind() != CONSTRUCTOR)
                    .collect(Collectors.toList());

                if (isConfigurationPropertiesType) {
                    // handle non @Inject, @Value fields as config properties
                    ElementFilter.fieldsIn(elementUtils.getAllMembers(classElement)).forEach(
                        field -> visitConfigurationProperty(field, o)
                    );
                } else {
                    TypeElement superClass = modelUtils.superClassFor(classElement);
                    if (superClass != null && !modelUtils.isObjectClass(superClass)) {
                        superClass.accept(this, o);
                    }
                }

                return scan(elements, o);
            } else {
                note("Visited unexpected classElement %s for %s", classElement.getSimpleName(), o);
                return null;
            }
        }

        @Override
        public Object visitExecutable(ExecutableElement method, Object o) {
            if (method.getKind() == ElementKind.CONSTRUCTOR) {
                // ctor is handled by visitType
                error("Unexpected call to visitExecutable for ctor %s of %s",
                    method.getSimpleName(), o);
                return null;
            }

            // handle @Bean annotation for @Factory class
            if (isFactoryType && annotationUtils.hasStereotype(method, Bean.class)) {
                visitBeanFactoryMethod(method, o);
                return null;
            }

            if (modelUtils.isStatic(method) || modelUtils.isAbstract(method)) {
                return null;
            }

            boolean injected = annotationUtils.hasStereotype(method, Inject.class);
            boolean postConstruct = annotationUtils.hasStereotype(method, PostConstruct.class);
            boolean preDestroy = annotationUtils.hasStereotype(method, PreDestroy.class);
            if (injected || postConstruct || preDestroy) {

                visitAnnotatedMethod(method, o);
                return null;
            }

            TypeElement declaringClassElement = modelUtils.classElementFor(method);
            if (modelUtils.isObjectClass(declaringClassElement)) {
                return null;
            }

            boolean isPublicMethod = method.getModifiers().contains(PUBLIC);
            boolean isExecutableMethod = annotationUtils.hasStereotype(method, Executable.class);
            if (isExecutableMethod || (isExecutableType && isPublicMethod)) {
                visitExecutableMethod(method, o);
                return null;
            }

            return null;
        }

        void visitBeanFactoryMethod(ExecutableElement method, Object o) {
            AnnotationMirror beanAnnotation = annotationUtils.findAnnotationWithStereotype(method, Bean.class);
            assert (beanAnnotation != null) : "bean annotation cannot be null";
            TypeMirror returnType = method.getReturnType();
            ExecutableElementParamInfo params = populateParameterData(method);

            BeanDefinitionWriter beanMethodWriter = createFactoryBeanMethodWriterFor(method, returnType);
            beanDefinitionWriters.put(method, beanMethodWriter);

            beanMethodWriter.visitBeanFactoryMethod(
                modelUtils.resolveTypeReference(this.concreteClass),
                method.getSimpleName().toString(),
                params.getParameters(),
                params.getQualifierTypes(),
                params.getGenericTypes()
            );

            annotationUtils.getAnnotationElementValue("preDestroy", beanAnnotation)
                .ifPresent(destroyMethodName -> {
                    TypeElement destroyMethodDeclaringClass = (TypeElement)typeUtils.asElement(returnType);
                    beanMethodWriter.visitPreDestroyMethod(
                        destroyMethodDeclaringClass.getQualifiedName(),
                        destroyMethodName
                    );
                });
        }

        void visitExecutableMethod(ExecutableElement method, Object o) {
            TypeMirror returnType = method.getReturnType();
            List<Object> returnTypeGenerics = genericUtils.resolveGenericTypes(returnType);
            ExecutableElementParamInfo params = populateParameterData(method);

            BeanDefinitionWriter writer = beanDefinitionWriters.get(this.concreteClass);

            writer.visitExecutableMethod(
                modelUtils.resolveTypeReference(concreteClass),
                modelUtils.resolveTypeReference(returnType),
                returnTypeGenerics,
                method.getSimpleName().toString(),
                params.getParameters(),
                params.getQualifierTypes(),
                params.getGenericTypes());
        }

        void visitAnnotatedMethod(ExecutableElement method, Object o) {
            ExecutableElementParamInfo params = populateParameterData(method);
            BeanDefinitionWriter writer = beanDefinitionWriters.get(this.concreteClass);
            TypeMirror returnType = method.getReturnType();
            TypeElement declaringClass = modelUtils.classElementFor(method);

            boolean isParent = !declaringClass.getQualifiedName().equals(this.concreteClass.getQualifiedName());
            ExecutableElement overridingMethod = modelUtils.overridingMethod(method, this.concreteClass).orElse(method);
            TypeElement overridingClass = modelUtils.classElementFor(overridingMethod);
            boolean overridden = isParent &&
                !overridingClass.getQualifiedName().equals(declaringClass.getQualifiedName());

            boolean isPackagePrivate = modelUtils.isPackagePrivate(method);
            boolean isPrivate = modelUtils.isPrivate(method);
            if (overridden && !(isPrivate && isPackagePrivate)) {
                // bail out if the method has been overridden, since it will have already been handled
                return;
            }

            PackageElement packageOfOverridingClass = elementUtils.getPackageOf(overridingMethod);
            PackageElement packageOfDeclaringClass = elementUtils.getPackageOf(declaringClass);
            boolean isPackagePrivateAndPackagesDiffer = overridden && isPackagePrivate &&
                !packageOfOverridingClass.getQualifiedName().equals(packageOfDeclaringClass.getQualifiedName());
            boolean requiresReflection = isPrivate || isPackagePrivateAndPackagesDiffer;
            boolean overriddenInjected = overridden && annotationUtils.hasStereotype(overridingMethod, Inject.class);

            if (isParent && isPackagePrivate && !isPackagePrivateAndPackagesDiffer && !overriddenInjected) {
                // bail out if the overridden method is package private and in the same package
                // and is not annotated with @Inject
                return;
            }
            if (!requiresReflection && modelUtils.isInheritedAndNotPublic(this.concreteClass, declaringClass, method)) {
                requiresReflection = true;
            }

            if (annotationUtils.hasStereotype(method, PostConstruct.class)) {
                writer.visitPostConstructMethod(
                    modelUtils.resolveTypeReference(declaringClass),
                    requiresReflection,
                    modelUtils.resolveTypeReference(returnType),
                    method.getSimpleName().toString(),
                    params.getParameters(),
                    params.getQualifierTypes(),
                    params.getGenericTypes()
                );
            } else if (annotationUtils.hasStereotype(method, PreDestroy.class)) {
                writer.visitPreDestroyMethod(
                    modelUtils.resolveTypeReference(declaringClass),
                    requiresReflection,
                    modelUtils.resolveTypeReference(returnType),
                    method.getSimpleName().toString(),
                    params.getParameters(),
                    params.getQualifierTypes(),
                    params.getGenericTypes()
                );
            } else if (annotationUtils.hasStereotype(method, Inject.class)) {
                writer.visitMethodInjectionPoint(
                    modelUtils.resolveTypeReference(declaringClass),
                    requiresReflection,
                    modelUtils.resolveTypeReference(returnType),
                    method.getSimpleName().toString(),
                    params.getParameters(),
                    params.getQualifierTypes(),
                    params.getGenericTypes()
                );
            } else {
                error("Unexpected call to visitAnnotatedMethod(%s)", method);
            }
        }

        @Override
        public Object visitVariable(VariableElement variable, Object o) {
            // assuming just fields, visitExecutable should be handling params for method calls
            if (modelUtils.isStatic(variable)) {
                return null;
            }

            if( variable.getKind() != FIELD) return null;

            boolean isInjected = annotationUtils.hasStereotype(variable, Inject.class);
            boolean isValue = !isInjected &&
                (annotationUtils.hasStereotype(variable, Value.class)); // || isConfigurationPropertiesType);

            if (isInjected || isValue) {
                BeanDefinitionWriter writer = beanDefinitionWriters.get(this.concreteClass);

                Object qualifierRef = annotationUtils.resolveQualifier(variable);
                boolean isPrivate = modelUtils.isPrivate(variable);
                // FIXME, add this logic from InjectTransform: isPrivate || isInheritedAndNotPublic(fieldNode, fieldNode.declaringClass, fieldNode.modifiers)
                boolean requiresReflection = isPrivate;

                if (!writer.isValidated() && annotationUtils.hasStereotype(variable, "javax.validation.Constraint")) {
                    writer.setValidated(true);
                }

                Name fieldName = variable.getSimpleName();
                TypeMirror type = variable.asType();
                Object fieldType = modelUtils.resolveTypeReference(type);

                TypeElement declaringClass = modelUtils.classElementFor(variable);

                if (isValue) {
                    writer.visitFieldValue(
                        declaringClass.getQualifiedName().toString(),
                        qualifierRef,
                        requiresReflection,
                        fieldType,
                        fieldName.toString(),
                        isConfigurationPropertiesType //isConfigurationPropertiesType
                    );
                } else {
                    writer.visitFieldInjectionPoint(
                        declaringClass.getQualifiedName().toString(),
                        qualifierRef,
                        requiresReflection,
                        fieldType,
                        fieldName.toString()
                    );
                }
            }
            return null;
        }

        public Object visitConfigurationProperty(VariableElement field, Object o) {
            Optional<ExecutableElement> setterMethod = modelUtils.findSetterMethodFor(field);
            boolean isInjected = annotationUtils.hasStereotype(field, Inject.class);
            boolean isValue = annotationUtils.hasStereotype(field, Value.class);

            boolean isMethodInjected = isInjected || (setterMethod.isPresent() && annotationUtils.hasStereotype(setterMethod.get(), Inject.class));
            if (!(isMethodInjected || isValue)) {
                // visitVariable didn't handle it
                BeanDefinitionWriter writer = beanDefinitionWriters.get(this.concreteClass);
                if (!writer.isValidated() && annotationUtils.hasStereotype(field, "javax.validation.Constraint")) {
                    writer.setValidated(true);
                }
                Object qualifierRef = annotationUtils.resolveQualifier(field);
                Object fieldType = modelUtils.resolveTypeReference(field.asType());
                List<Object> genericTypes;
                TypeKind typeKind = field.asType().getKind();
                if (!(typeKind.isPrimitive() || typeKind == ARRAY)) {
                    genericTypes = ((DeclaredType)field.asType()).getTypeArguments()
                        .stream()
                        .map(TypeMirror::toString)
                        .collect(Collectors.toList());
                } else {
                    genericTypes = Collections.emptyList();
                }

                TypeElement declaringClass = modelUtils.classElementFor(field);


                if (setterMethod.isPresent()) {
                    ExecutableElement method = setterMethod.get();
                    writer.visitSetterValue(
                        modelUtils.resolveTypeReference(declaringClass),
                        qualifierRef,
                        modelUtils.requiresReflection(method),
                        fieldType,
                        field.getSimpleName().toString(),
                        method.getSimpleName().toString(),
                        genericTypes,
                        isConfigurationPropertiesType);
                }
                else {
                    boolean isPrivate = modelUtils.isPrivate(field);
                    boolean requiresReflection = isPrivate || isInheritedAndNotPublic(modelUtils.classElementFor(field), field.getModifiers());

                    Object declaringType = modelUtils.resolveTypeReference(declaringClass);
                    String fieldName = field.getSimpleName().toString();
                    writer.visitFieldValue(
                        declaringType,
                        qualifierRef,
                        requiresReflection,
                        fieldType,
                        fieldName,
                        isConfigurationPropertiesType);
                }
            }

            return null;
        }

        protected boolean isInheritedAndNotPublic(TypeElement declaringClass, Set<Modifier> modifiers) {
            PackageElement declaringPackage = elementUtils.getPackageOf(declaringClass);
            PackageElement concretePackage = elementUtils.getPackageOf(concreteClass);
            return !declaringClass.equals(concreteClass) &&
                    !declaringPackage.equals(concretePackage) &&
                    !(modifiers.contains(Modifier.PUBLIC));
        }

        @Override
        public Object visitTypeParameter(TypeParameterElement e, Object o) {
            note("Visit param %s for %s", e.getSimpleName(), o);
            return super.visitTypeParameter(e, o);
        }

        @Override
        public Object visitUnknown(Element e, Object o) {
            note("Visit unknown %s for %s", e.getSimpleName(), o);
            return super.visitUnknown(e, o);
        }

        private BeanDefinitionWriter createBeanDefinitionWriterFor(TypeElement typeElement) {
            TypeMirror providerTypeParam =
                genericUtils.interfaceGenericTypeFor(typeElement, Provider.class);
            AnnotationMirror scopeAnn =
                annotationUtils.findAnnotationWithStereotype(typeElement, Scope.class);
            AnnotationMirror singletonAnn =
                annotationUtils.findAnnotationWithStereotype(typeElement, Singleton.class);

            PackageElement packageElement = elementUtils.getPackageOf(typeElement);
            String beanClassName = modelUtils.simpleBinaryNameFor(typeElement);

            return new BeanDefinitionWriter(
                packageElement.getQualifiedName().toString(),
                beanClassName,
                providerTypeParam == null
                    ? elementUtils.getBinaryName(typeElement).toString()
                    : providerTypeParam.toString(),
                scopeAnn == null
                    ? null
                    : scopeAnn.getAnnotationType().toString(),
                singletonAnn != null);
        }

        private BeanDefinitionWriter createFactoryBeanMethodWriterFor(ExecutableElement method, TypeMirror producedType) {
            AnnotationMirror scopeAnn =
                annotationUtils.findAnnotationWithStereotype(method, Scope.class);
            AnnotationMirror singletonAnn =
                annotationUtils.findAnnotationWithStereotype(method, Singleton.class);

            Element element = typeUtils.asElement(producedType);
            TypeElement producedElement = modelUtils.classElementFor(element);

            PackageElement producedPackageElement = elementUtils.getPackageOf(producedElement);
            PackageElement definingPackageElement = elementUtils.getPackageOf(concreteClass);

            return new BeanDefinitionWriter(
                producedPackageElement.getQualifiedName().toString(),
                modelUtils.simpleBinaryNameFor(producedElement),
                scopeAnn == null
                    ? null
                    : scopeAnn.getAnnotationType().toString(),
                singletonAnn != null,
                definingPackageElement.getQualifiedName().toString()
            );
        }

        ExecutableElement publicConstructorFor(TypeElement classElement) {
            // following logic of InjectTransform.groovy
            List<ExecutableElement> constructors = modelUtils.findPublicConstructors(classElement);
            if (constructors.isEmpty()) {
                return null;
            }
            if (constructors.size() == 1) {
                return constructors.get(0);
            }
            Optional<ExecutableElement> element = constructors.stream().filter(ctor -> {
                List<? extends VariableElement> parameters = ctor.getParameters();
                return !parameters.stream()
                    .filter(param -> Objects.nonNull(param.getAnnotation(Inject.class)))
                    .collect(Collectors.toList())
                    .isEmpty();
            }).findFirst();
            return element.isPresent()? element.get() : null;
        }

        private ExecutableElementParamInfo populateParameterData(ExecutableElement element) {
            ExecutableElementParamInfo params = new ExecutableElementParamInfo();
            if (element == null) {
                return params;
            }
            element.getParameters().forEach(paramElement -> {

                String argName = paramElement.getSimpleName().toString();
                TypeMirror typeMirror = paramElement.asType();
                TypeKind kind = typeMirror.getKind();
                Object qualifier = annotationUtils.resolveQualifier(paramElement);
                if (qualifier != null) {
                    params.addQualifierType(argName, qualifier);
                }

                if (kind == ARRAY) {
                    ArrayType arrayType = (ArrayType) typeMirror;
                    TypeMirror componentType = arrayType.getComponentType();
                    params.addParameter(argName, modelUtils.resolveTypeReference(arrayType));
                    params.addGenericTypes(argName, Collections.singletonList(modelUtils.resolveTypeReference(componentType)));
                } else if (kind == DECLARED) {
                    DeclaredType declaredType = (DeclaredType) typeMirror;

                    TypeElement typeElement = elementUtils.getTypeElement(typeUtils.erasure(declaredType).toString());
                    assert (typeElement != null) : "typeElement cannot be null";

                    params.addParameter(argName, modelUtils.resolveTypeReference(typeElement));
                    List<Object> typeParams = genericUtils.resolveGenericTypes(declaredType);
                    if (!typeParams.isEmpty()) {
                        params.addGenericTypes(argName, typeParams);
                    }
                } else if (kind.isPrimitive()) {
                    String typeName = typeMirror.toString();
                    Object argType = modelUtils.classOfPrimitiveFor(typeName);
                    params.addParameter(argName, argType);
                } else {
                    error(element, "Unexpected kind %s for param %s of element %s", kind, typeMirror, element);
                }
            });

            return params;
        }
    }
}


