/*Copyright (C) 2017 Roland Hauser, <sourcepond@gmail.com>

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.*/
package ch.sourcepond.spring.web.blueprint.internal;

import org.osgi.framework.*;
import org.osgi.service.blueprint.container.BlueprintContainer;
import org.osgi.service.blueprint.container.NoSuchComponentException;
import org.osgi.service.blueprint.reflect.*;
import org.slf4j.Logger;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyEditorRegistrar;
import org.springframework.beans.PropertyEditorRegistry;
import org.springframework.beans.TypeConverter;
import org.springframework.beans.factory.*;
import org.springframework.beans.factory.config.*;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.MessageSource;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.support.DelegatingMessageSource;
import org.springframework.core.ResolvableType;
import org.springframework.core.convert.ConversionService;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StringValueResolver;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.ServletContextResourcePatternResolver;
import org.springframework.web.context.support.StandardServletEnvironment;

import javax.servlet.ServletContext;
import java.beans.PropertyEditor;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.time.Instant;
import java.util.*;

import static ch.sourcepond.spring.web.blueprint.internal.ResourceFinderClassLoader.getBundleClassLoader;
import static java.lang.Thread.currentThread;
import static java.time.Instant.now;
import static java.util.Collections.emptyMap;
import static java.util.Objects.requireNonNull;
import static org.osgi.framework.ServiceEvent.*;
import static org.slf4j.LoggerFactory.getLogger;
import static org.springframework.context.support.AbstractApplicationContext.MESSAGE_SOURCE_BEAN_NAME;

/**
 * {@link BeanFactory} implementation which adapts to {@link BlueprintContainer}.
 * An newly created instance will wait until the {@link BlueprintContainer} associated with the
 * bundle of {@link BundleContext} specified has been started and registered as service before
 * it is in operational state.
 */
public final class BlueprintApplicationContext implements WebApplicationContext, ConfigurableBeanFactory, ServiceListener {
    private static final Logger LOG = getLogger(BlueprintApplicationContext.class);
    static final String BLUEPRINT_CONTAINER_CONTAINER_HAS_BEEN_SHUTDOWN = "BlueprintContainer container has been shutdown";

    /**
     * Aliases are currently not supported by the Blueprint specification.
     */
    static final String[] EMPTY = new String[0];

    /**
     * Service property name of the corresponding WABs symbolic-name. This
     * property is necessary in order to retrieve the WABs
     * {@link BlueprintContainer} through the OSGi service registry. See OSGi
     * Enterprise specification R5 (page 230, section 121.3.10).
     */
    static final String OSGI_BLUEPRINT_CONTAINER_SYMBOLIC_NAME = "osgi.blueprint.container.symbolicname";

    /**
     * Service property name of the corresponding WABs version. This property is
     * necessary in order to retrieve the WABs {@link BlueprintContainer}
     * through the OSGi service registry. See OSGi Enterprise specification R5
     * (page 230, section 121.3.10).
     */
    static final String OSGI_BLUEPRINT_CONTAINER_VERSION = "osgi.blueprint.container.version";

    private final Instant startTime = now();
    private final ResourcePatternResolver resolver;
    private final ServletContext servletContext;
    private final Environment environment;
    private final BundleContext bundleContext;
    private final String filter;
    private boolean destroyed;
    private volatile MessageSource source;
    private volatile ClassLoader classLoader;
    private volatile ClassLoader tempClassLoader;
    private volatile BlueprintContainer container;
    private volatile Set<String> componentIds;

    /**
     * Creates a new instance of this class. The instance will wait until
     * the {@link BlueprintContainer} associated with the bundle of Bundle-Context
     * specified has been started and registered as service before it is in
     * operational state.
     *
     * @param servletContext
     * @param bundleContext
     */
    public BlueprintApplicationContext(final ServletContext servletContext, final BundleContext bundleContext) {
        this.servletContext = servletContext;
        final StandardServletEnvironment environment = new StandardServletEnvironment();
        environment.initPropertySources(servletContext, null);
        this.environment = environment;
        this.bundleContext = requireNonNull(bundleContext, "Bundle-Context is null");
        final Bundle bundle = bundleContext.getBundle();
        filter = "(&(" + Constants.OBJECTCLASS + "="
                + BlueprintContainer.class.getName() + ")("
                + OSGI_BLUEPRINT_CONTAINER_SYMBOLIC_NAME + "="
                + bundle.getSymbolicName() + ")("
                + OSGI_BLUEPRINT_CONTAINER_VERSION + "="
                + bundle.getVersion() + "))";
        classLoader = getBundleClassLoader(bundle);
        resolver = new BundleResourcePatternResolver(bundleContext.getBundle(), new ServletContextResourcePatternResolver(this));
    }

    private Bundle getBundle() {
        return bundleContext.getBundle();
    }

    public String getFilter() {
        return filter;
    }

    private BlueprintContainer findExistingBlueprintContainer() {
        BlueprintContainer container = null;
        try {
            final ServiceReference<?>[] refs = bundleContext.getServiceReferences((String) null, filter);
            if (refs != null) {
                container = (BlueprintContainer) bundleContext.getService(refs[0]);
            }
        } catch (final InvalidSyntaxException e) {
            // This should never happen
            throw new IllegalStateException(e);
        }
        return container;
    }

    private BlueprintContainer getContainer() {
        if (container == null) {
            synchronized (this) {
                if (container == null) {
                    container = findExistingBlueprintContainer();
                    try {
                        while (container == null && !destroyed) {
                            wait();
                        }
                    } catch (final InterruptedException e) {
                        currentThread().interrupt();
                        throw new BeanDefinitionStoreException("Wait for BlueprintContainer interrupted", e);
                    }

                    if (container == null) {
                        throw new BeanDefinitionStoreException(BLUEPRINT_CONTAINER_CONTAINER_HAS_BEEN_SHUTDOWN);
                    }
                }
            }
        }
        return container;
    }

    private synchronized void blueprintContainerRegistered(final ServiceReference<?> pReference) {
        container = (BlueprintContainer) bundleContext.getService(pReference);
        notifyAll();
    }

    private synchronized void blueprintContainerUnregistered() {
        container = null;
        destroyed = true;
        notifyAll();
    }

    @Override
    public void serviceChanged(final ServiceEvent serviceEvent) {
        switch (serviceEvent.getType()) {
            case UNREGISTERING:
            case MODIFIED_ENDMATCH: {
                blueprintContainerUnregistered();
                break;
            }
            case REGISTERED: {
                blueprintContainerRegistered(serviceEvent.getServiceReference());
                break;
            }
            default: {
                // noop
            }
        }
    }

    private Set<String> getFilteredComponentIds() {
        Set<String> ids = componentIds;
        if (ids == null) {
            final BlueprintContainer container = getContainer();
            ids = new HashSet<>(container.getComponentIds());
            ids.removeIf(id -> isIncompatible(container.getComponentMetadata(id)));
            componentIds = ids;
        }
        return ids;
    }

    @Override
    public boolean containsBeanDefinition(String beanName) {
        return getFilteredComponentIds().contains(beanName);
    }

    @Override
    public int getBeanDefinitionCount() {
        return getFilteredComponentIds().size();
    }

    @Override
    public String[] getBeanDefinitionNames() {
        return getFilteredComponentIds().toArray(EMPTY);
    }

    @Override
    public String[] getBeanNamesForType(ResolvableType type) {
        return getBeanNamesForType(type.getRawClass());
    }

    public String[] getBeanNamesForType(final Class<?> type) {
        return getBeanNamesForType(type, false, false);
    }

    @Override
    public String[] getBeanNamesForType(Class<?> type, boolean includeNonSingletons, boolean allowEagerInit) {
        final Set<String> beanNames = new HashSet<>();
        for (final String id : getFilteredComponentIds()) {
            try {
                final Class<?> cl = findType(findMetadata(id));
                if (type.isAssignableFrom(cl)) {
                    beanNames.add(id);
                }
            } catch (final Exception e) {
                LOG.warn(e.getMessage(), e);
            }
        }
        return beanNames.toArray(EMPTY);
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type) throws BeansException {
        return getBeansOfType(type, false, false);
    }

    @Override
    public <T> Map<String, T> getBeansOfType(Class<T> type, boolean includeNonSingletons, boolean allowEagerInit) throws BeansException {
        final Map<String, T> beans = new HashMap<>();
        for (final String name : getBeanNamesForType(type)) {
            beans.put(name, getBean(name, type));
        }
        return beans;
    }

    @Override
    public String[] getBeanNamesForAnnotation(Class<? extends Annotation> annotationType) {
        // Not supported by BlueprintContainer
        return EMPTY;
    }

    @Override
    public Map<String, Object> getBeansWithAnnotation(Class<? extends Annotation> annotationType) throws BeansException {
        // Not supported by BlueprintContainer
        return emptyMap();
    }

    @Override
    public <A extends Annotation> A findAnnotationOnBean(String beanName, Class<A> annotationType) throws NoSuchBeanDefinitionException {
        // Throw NoSuchBeanDefinitionException if bean does not exist
        getBean(beanName);

        // Not supported by BlueprintContainer
        return null;
    }

    @Override
    public Object getBean(final String s) throws BeansException {
        try {
            return getContainer().getComponentInstance(s);
        } catch (final NoSuchComponentException e) {
            final NoSuchBeanDefinitionException nsbe = new NoSuchBeanDefinitionException(
                    s);
            nsbe.initCause(e);
            throw nsbe;
        }
    }

    @Override
    public <T> T getBean(final String s, final Class<T> aClass) throws BeansException {
        // Get instance; never null
        final Object instance = getBean(s);

        if (aClass != null
                && !aClass.isAssignableFrom(instance.getClass())) {
            throw new BeanNotOfRequiredTypeException(s, aClass,
                    instance.getClass());
        }
        return (T) instance;
    }

    @Override
    public <T> T getBean(final Class<T> aClass) throws BeansException {
        requireNonNull(aClass, "Class is null");
        final BlueprintContainer container = getContainer();
        final Set<String> ids = getFilteredComponentIds();
        final Map<String, Object> beans = new HashMap<>(ids.size());
        ids.forEach(id -> beans.put(id, getContainer().getComponentInstance(id)));

        for (final Iterator<Object> it = beans.values().iterator(); it.hasNext(); ) {
            if (!aClass.isAssignableFrom(it.next().getClass())) {
                it.remove();
            }
        }

        if (beans.isEmpty()) {
            throw new NoSuchBeanDefinitionException(aClass);
        }

        if (beans.size() > 1) {
            throw new NoUniqueBeanDefinitionException(aClass, beans.keySet());
        }

        return (T) beans.values().iterator().next();
    }

    @Override
    public Object getBean(final String s, final Object... objects) throws BeansException {
        // objects argument ignored because object creation lies in the
        // responsibility of the BlueprintServletContainerInitializer-container.
        return getBean(s);
    }

    @Override
    public <T> T getBean(final Class<T> aClass, final Object... objects) throws BeansException {
        // objects argument ignored because object creation lies in the
        // responsibility of the BlueprintServletContainerInitializer-container.
        return getBean(aClass);
    }

    @Override
    public boolean containsBean(final String s) {
        try {
            getContainer().getComponentInstance(s);
            return true;
        } catch (final NoSuchComponentException e) {
            // noop
            LOG.trace(e.getMessage(), e);
        }
        return false;
    }

    private boolean hasScope(final String pScope, final String s) throws NoSuchBeanDefinitionException {
        boolean singleton;
        try {
            final ComponentMetadata m = getContainer().getComponentMetadata(s);
            singleton = (m instanceof BeanMetadata) && pScope.equals(((BeanMetadata) m).getScope());
        } catch (final NoSuchComponentException e) {
            throw new NoSuchBeanDefinitionException(s);
        }
        return singleton;
    }


    @Override
    public boolean isSingleton(final String s) throws NoSuchBeanDefinitionException {
        return hasScope(SCOPE_SINGLETON, s);
    }

    @Override
    public boolean isPrototype(final String s) throws NoSuchBeanDefinitionException {
        return hasScope(SCOPE_PROTOTYPE, s);
    }

    @Override
    public boolean isTypeMatch(final String s, final ResolvableType resolvableType) throws NoSuchBeanDefinitionException {
        return resolvableType.isAssignableFrom(getBean(s).getClass());
    }

    @Override
    public boolean isTypeMatch(final String s, final Class<?> aClass) throws NoSuchBeanDefinitionException {
        return aClass.isAssignableFrom(getBean(s).getClass());
    }

    @Override
    public Class<?> getType(final String s) throws NoSuchBeanDefinitionException {
        try {
            return findType(findMetadata(s));
        } catch (final ClassNotFoundException | NoSuchMethodException e) {
            final NoSuchBeanDefinitionException ex = new NoSuchBeanDefinitionException(s);
            ex.initCause(e);
            throw ex;
        }
    }

    private boolean isIncompatible(final ComponentMetadata metadata) {
        return !(metadata instanceof BeanMetadata) && !(metadata instanceof ServiceReferenceMetadata);
    }

    private ComponentMetadata findMetadata(final String id) {
        try {
            final ComponentMetadata metadata = getContainer().getComponentMetadata(id);

            if (isIncompatible(metadata)) {
                throw new NoSuchComponentException("Actual metadata-class "
                        + metadata.getClass() + " is not assignable from "
                        + BeanMetadata.class.getName() + " or "
                        + ServiceReferenceMetadata.class, id);
            }

            return metadata;
        } catch (final NoSuchComponentException e) {
            final NoSuchBeanDefinitionException nsbe = new NoSuchBeanDefinitionException(
                    id);
            nsbe.initCause(e);
            throw nsbe;
        }
    }

    private String getComponentId(final Target target) {
        final String componentId;
        if (target instanceof ComponentMetadata) {
            componentId = ((ComponentMetadata) target).getId();
        } else { // It can only be a RefMetadata
            componentId = ((RefMetadata) target).getComponentId();
        }
        return componentId;
    }

    private Class<?> findType(final ComponentMetadata metadata) throws ClassNotFoundException, NoSuchMethodException {
        assert metadata != null : "metadata cannot be null";

        Class<?> clazz = null;

        if (metadata instanceof BeanMetadata) {
            final BeanMetadata beanMetadata = (BeanMetadata) metadata;
            final String factoryMethodNameOrNull = beanMetadata
                    .getFactoryMethod();
            final String className = beanMetadata.getClassName();

            // Class name can be null when a factory component was used to construct the bean.
            if (className == null) {

                // We need a factory-method name at this point; throw an exception if not so.
                if (factoryMethodNameOrNull == null) {
                    throw new IllegalStateException(
                            "Invalid metadata: class-name nor factory-method is provided! "
                                    + metadata);
                }

                // Get the metadata of the factory component and validate it.
                final Target factoryComponent = beanMetadata
                        .getFactoryComponent();
                if (factoryComponent == null) {
                    throw new IllegalStateException(
                            "No class-name nor a factory component has been specified!");
                }

                final ComponentMetadata factoryComponentMetadata = findMetadata(getComponentId(factoryComponent));

                // The factory-component itself could also be constructed through
                // another factory. Because this, we need to call this method
                // recursively
                final Class<?> factoryComponentClass = findType(factoryComponentMetadata);

                // Almost at the end; what we finally need is the return type of the factory-method
                clazz = factoryComponentClass.getMethod(factoryMethodNameOrNull).getReturnType();
            } else if (factoryMethodNameOrNull != null) {
                // A static factory-method should be defined; get its return type.
                clazz = determineFactoryReturnType(beanMetadata);
            } else {
                // Simply load the class with the class-name specified
                clazz = loadClass(className);
            }
        } else if (metadata instanceof ServiceReferenceMetadata) {
            final ServiceReferenceMetadata referenceMetadata = (ServiceReferenceMetadata) metadata;

            // getId() can be null when the reference-element is nested; skip it
            // because we must not consider nested elements!
            if (referenceMetadata.getInterface() != null) {
                clazz = loadClass(referenceMetadata.getInterface());
            }
        } else {
            throw new CannotLoadBeanClassException(bundleContext.getBundle().toString(),
                    metadata.getId(), metadata.toString(),
                    new ClassNotFoundException());
        }

        return clazz;
    }

    private Class<?> determineFactoryReturnType(final BeanMetadata metadata) throws
            ClassNotFoundException, NoSuchMethodException {
        final Class<?> factoryClass = loadClass(metadata.getClassName());
        return factoryClass.getMethod(metadata.getFactoryMethod()).getReturnType();
    }

    private Class<?> loadClass(final String className) throws ClassNotFoundException {
        return bundleContext.getBundle().loadClass(className);
    }

    @Override
    public String[] getAliases(final String s) {
        return EMPTY;
    }

    @Override
    public void setParentBeanFactory(final BeanFactory parentBeanFactory) throws IllegalStateException {
        throw new IllegalStateException("Settings a parent BeanFactory is not supported");
    }

    @Override
    public void setBeanClassLoader(final ClassLoader beanClassLoader) {
        classLoader = beanClassLoader;
    }

    @Override
    public ClassLoader getBeanClassLoader() {
        return classLoader;
    }

    @Override
    public void setTempClassLoader(final ClassLoader tempClassLoader) {
        this.tempClassLoader = tempClassLoader;
    }

    @Override
    public ClassLoader getTempClassLoader() {
        return tempClassLoader;
    }

    @Override
    public void setCacheBeanMetadata(final boolean cacheBeanMetadata) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCacheBeanMetadata() {
        return false;
    }

    @Override
    public void setBeanExpressionResolver(final BeanExpressionResolver resolver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BeanExpressionResolver getBeanExpressionResolver() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setConversionService(final ConversionService conversionService) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ConversionService getConversionService() {
        // Not supported
        return null;
    }

    @Override
    public void addPropertyEditorRegistrar(final PropertyEditorRegistrar registrar) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerCustomEditor(final Class<?> requiredType, final Class<? extends PropertyEditor> propertyEditorClass) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void copyRegisteredEditorsTo(final PropertyEditorRegistry registry) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTypeConverter(final TypeConverter typeConverter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public TypeConverter getTypeConverter() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void addEmbeddedValueResolver(final StringValueResolver valueResolver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean hasEmbeddedValueResolver() {
        return false;
    }

    @Override
    public String resolveEmbeddedValue(final String value) {
        return value;
    }

    @Override
    public void addBeanPostProcessor(final BeanPostProcessor beanPostProcessor) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getBeanPostProcessorCount() {
        return 0;
    }

    @Override
    public void registerScope(final String scopeName, final Scope scope) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getRegisteredScopeNames() {
        return EMPTY;
    }

    @Override
    public Scope getRegisteredScope(final String scopeName) {
        // noop
        return null;
    }

    @Override
    public AccessControlContext getAccessControlContext() {
        return AccessController.getContext();
    }

    @Override
    public void copyConfigurationFrom(final ConfigurableBeanFactory otherFactory) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void registerAlias(final String beanName, final String alias) throws BeanDefinitionStoreException {
        throw new UnsupportedOperationException();
    }

    @Override
    public void resolveAliases(final StringValueResolver valueResolver) {
        throw new UnsupportedOperationException();
    }

    @Override
    public BeanDefinition getMergedBeanDefinition(final String beanName) throws NoSuchBeanDefinitionException {
        return null;
    }

    @Override
    public boolean isFactoryBean(final String name) throws NoSuchBeanDefinitionException {
        return false;
    }

    @Override
    public void setCurrentlyInCreation(final String beanName, final boolean inCreation) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isCurrentlyInCreation(final String beanName) {
        return false;
    }

    @Override
    public void registerDependentBean(final String beanName, final String dependentBeanName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getDependentBeans(final String beanName) {
        return EMPTY;
    }

    @Override
    public String[] getDependenciesForBean(final String beanName) {
        return EMPTY;
    }

    @Override
    public void destroyBean(final String beanName, final Object beanInstance) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroyScopedBean(final String beanName) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void destroySingletons() {
        // noop
    }

    @Override
    public BeanFactory getParentBeanFactory() {
        return null;
    }

    @Override
    public boolean containsLocalBean(final String name) {
        return containsBean(name);
    }

    @Override
    public void registerSingleton(final String beanName, final Object singletonObject) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Object getSingleton(final String beanName) {
        return getBean(beanName);
    }

    @Override
    public boolean containsSingleton(final String beanName) {
        return getFilteredComponentIds().contains(beanName);
    }

    @Override
    public String[] getSingletonNames() {
        return getFilteredComponentIds().toArray(new String[0]);
    }

    @Override
    public int getSingletonCount() {
        return getFilteredComponentIds().size();
    }

    @Override
    public Object getSingletonMutex() {
        return this;
    }


    @Override
    public String getId() {
        return getBundle().getSymbolicName();
    }

    @Override
    public String getApplicationName() {
        return "";
    }

    @Override
    public String getDisplayName() {
        return getId();
    }

    @Override
    public long getStartupDate() {
        return startTime.toEpochMilli();
    }

    @Override
    public ApplicationContext getParent() {
        // Noop
        return null;
    }

    @Override
    public AutowireCapableBeanFactory getAutowireCapableBeanFactory() throws IllegalStateException {
        throw new IllegalStateException("AutowireCapableBeanFactory not supported");
    }

    @Override
    public void publishEvent(ApplicationEvent event) {
        LOG.debug("noop");
    }

    @Override
    public void publishEvent(Object event) {
        LOG.debug("noop");
    }

    private MessageSource getMessageSource() {
        MessageSource source = this.source;
        if (source == null) {
            try {
                source = getBean(MESSAGE_SOURCE_BEAN_NAME, MessageSource.class);
            } catch (final NoSuchBeanDefinitionException e) {
                source = new DelegatingMessageSource();
            }
            this.source = source;
        }
        return source;
    }

    @Override
    public String getMessage(String code, Object[] args, String defaultMessage, Locale locale) {
        return getMessageSource().getMessage(code, args, defaultMessage, locale);
    }

    @Override
    public String getMessage(String code, Object[] args, Locale locale) throws NoSuchMessageException {
        return getMessageSource().getMessage(code, args, locale);
    }

    @Override
    public String getMessage(MessageSourceResolvable resolvable, Locale locale) throws NoSuchMessageException {
        return getMessageSource().getMessage(resolvable, locale);
    }

    @Override
    public Environment getEnvironment() {
        return environment;
    }

    @Override
    public Resource[] getResources(String locationPattern) throws IOException {
        return resolver.getResources(locationPattern);
    }

    @Override
    public Resource getResource(String location) {
        return resolver.getResource(location);
    }

    @Override
    public ClassLoader getClassLoader() {
        return getBeanClassLoader();
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }
}
