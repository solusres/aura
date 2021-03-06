/*
 * Copyright (C) 2013 salesforce.com, inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auraframework.impl.system;

import java.lang.ref.WeakReference;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.apache.log4j.Logger;
import org.auraframework.Aura;
import org.auraframework.def.*;
import org.auraframework.def.DefDescriptor.DefType;
import org.auraframework.impl.root.DependencyDefImpl;
import org.auraframework.service.DefinitionService;
import org.auraframework.service.LoggingService;
import org.auraframework.system.*;
import org.auraframework.system.AuraContext.Mode;
import org.auraframework.throwable.AuraRuntimeException;
import org.auraframework.throwable.NoAccessException;
import org.auraframework.throwable.quickfix.DefinitionNotFoundException;
import org.auraframework.throwable.quickfix.QuickFixException;
import org.auraframework.util.text.Hash;

import com.google.common.base.Optional;
import com.google.common.cache.*;
import com.google.common.collect.*;

/**
 * Overall Master definition registry implementation, there be dragons here.
 * 
 * This 'master' definition registry is actually a single threaded, per request registry that caches certain things in
 * what is effectively a thread local cache. This means that once something is pulled into the local thread, it will not
 * change.
 * 
 */
public class MasterDefRegistryImpl implements MasterDefRegistry {
    private static final Set<DefType> securedDefTypes = Sets.immutableEnumSet(DefType.APPLICATION, DefType.COMPONENT,
            DefType.CONTROLLER, DefType.ACTION);
    private static final Set<String> unsecuredPrefixes = ImmutableSet.of("aura");
    private static final Set<String> unsecuredNamespaces = ImmutableSet.of("aura", "ui", "os", "auradev",
            "org.auraframework");
    private static final Set<String> unsecuredNonProductionNamespaces = ImmutableSet.of("auradev");

    private static final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private static final Lock rLock = rwLock.readLock();
    private static final Lock wLock = rwLock.writeLock();

    private final static int DEFINITION_CACHE_SIZE = 4096;
    private final static int DEPENDENCY_CACHE_SIZE = 100;
    private final static int STRING_CACHE_SIZE = 100;
    private static final Logger logger = Logger.getLogger(MasterDefRegistryImpl.class);

    /**
     * A dependency entry for a uid+descriptor.
     * 
     * This entry is created for each descriptor that a context uses at the top level. It is cached globally and
     * locally. The second version of the entry (with a quick fix) is only ever cached locally.
     * 
     * all values are final, and unmodifiable.
     */
    private static class DependencyEntry {
        public final String uid;
        public final long lastModTime;
        public final Set<DefDescriptor<?>> dependencies;
        public final List<ClientLibraryDef> clientLibraries;
        public final QuickFixException qfe;

        public DependencyEntry(String uid, Set<DefDescriptor<?>> dependencies, long lastModTime,
                               List<ClientLibraryDef> clientLibraries) {
            this.uid = uid;
            this.dependencies = dependencies;
            this.clientLibraries = Collections.unmodifiableList(clientLibraries);
            this.lastModTime = lastModTime;
            this.qfe = null;
        }

        public DependencyEntry(QuickFixException qfe) {
            this.uid = null;
            this.dependencies = null;
            this.clientLibraries = null;
            this.lastModTime = 0;
            this.qfe = qfe;
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();

            sb.append(uid);
            sb.append(" : ");
            if (qfe != null) {
                sb.append(qfe);
            } else {
                sb.append("[");
                sb.append(lastModTime);
                sb.append("] :");
                sb.append(dependencies);
            }
            return sb.toString();
        }
    }

    private final static Cache<DefDescriptor<?>, Boolean> existsCache = CacheBuilder.newBuilder()
            .initialCapacity(DEFINITION_CACHE_SIZE).maximumSize(DEFINITION_CACHE_SIZE).recordStats().softValues()
            .build();

    private final static Cache<DefDescriptor<?>, Optional<? extends Definition>> defsCache = CacheBuilder.newBuilder()
            .initialCapacity(DEFINITION_CACHE_SIZE).maximumSize(DEFINITION_CACHE_SIZE).recordStats().softValues()
            .build();

    private final static Cache<String, DependencyEntry> depsCache = CacheBuilder.newBuilder()
            .initialCapacity(DEPENDENCY_CACHE_SIZE).maximumSize(DEPENDENCY_CACHE_SIZE).recordStats().softValues()
            .build();

    private final static Cache<String, String> stringsCache = CacheBuilder.newBuilder()
            .initialCapacity(STRING_CACHE_SIZE).maximumSize(STRING_CACHE_SIZE).recordStats().softValues().build();

    private final static Cache<String, Set<DefDescriptor<?>>> descriptorFilterCache = CacheBuilder.newBuilder()
            .initialCapacity(DEPENDENCY_CACHE_SIZE).maximumSize(DEPENDENCY_CACHE_SIZE).recordStats().softValues().build();

    /**
     * A local dependencies cache.
     * 
     * We store both by descriptor and by uid. The descriptor keys must include the type, as the qualified name is not
     * sufficient to distinguish it. In the case of the UID, we presume that we are safe.
     * 
     * The two keys stored in the local cache are:
     * <ul>
     * <li>The UID, which should be sufficiently unique for a single request.</li>
     * <li>The type+qualified name of the descriptor. We store this to avoid construction in the case where we don't
     * have a UID. This is presumed safe because we assume that a single session will have a consistent set of
     * permissions</li>
     * </ul>
     */
    private final Map<String, DependencyEntry> localDependencies = Maps.newHashMap();

    private final RegistryTrie delegateRegistries;

    private final Map<DefDescriptor<? extends Definition>, Definition> defs = Maps.newHashMap();

    private final boolean useCache = true;

    private Set<DefDescriptor<? extends Definition>> localDescs = null;

    private final Set<DefDescriptor<?>> accessCache = Sets.newLinkedHashSet();

    private CompileContext currentCC;

    private SecurityProviderDef securityProvider;
    private DefDescriptor<? extends BaseComponentDef> lastRootDesc;

    public MasterDefRegistryImpl(DefRegistry<?>... registries) {
        delegateRegistries = new RegistryTrie(registries);
    }

    private boolean isCacheable(DefRegistry<?> reg) {
        return useCache && reg.isCacheable();
    }

    @Override
    public Set<DefDescriptor<?>> find(DescriptorFilter matcher) {
        final String filterKey = matcher.toString();
        Set<DefRegistry<?>> registries = delegateRegistries.getRegistries(matcher);
        Set<DefDescriptor<?>> matched = Sets.newHashSet();

        rLock.lock();
        try {
        for (DefRegistry<?> reg : registries) {
            //
            // This could be a little dangerous, but unless we force all of our
            // registries to implement find, this is necessary.
            //
            if (reg.hasFind()) {
                Set<DefDescriptor<?>> registryResults = null;

                if (isCacheable(reg)) {
                    // cache results per registry
                    String cacheKey = filterKey + "|" + reg.toString();
                    registryResults = descriptorFilterCache.getIfPresent(cacheKey);
                    if (registryResults == null) {
                        registryResults = reg.find(matcher);
                        descriptorFilterCache.put(cacheKey, registryResults);
                    }
                } else {
                    registryResults = reg.find(matcher);
                }

                matched.addAll(registryResults);
            }
        }
        if (localDescs != null) {
            for (DefDescriptor<? extends Definition> desc : localDescs) {
                if (matcher.matchDescriptor(desc)) {
                    matched.add(desc);
                }
            }
        }
        } finally {
            rLock.unlock();
        }

        return matched;
    }

    @Override
    public <D extends Definition> Set<DefDescriptor<D>> find(DefDescriptor<D> matcher) {
        Set<DefDescriptor<D>> matched;
        if (matcher.getNamespace().equals("*")) {
            matched = new LinkedHashSet<DefDescriptor<D>>();
            String qualifiedNamePattern = null;
            switch (matcher.getDefType()) {
            case CONTROLLER:
            case TESTSUITE:
            case MODEL:
            case RENDERER:
            case HELPER:
            case STYLE:
            case TYPE:
            case RESOURCE:
            case PROVIDER:
            case SECURITY_PROVIDER:
                qualifiedNamePattern = "%s://%s.%s";
                break;
            case ATTRIBUTE:
            case LAYOUT:
            case LAYOUT_ITEM:
            case TESTCASE:
            case APPLICATION:
            case COMPONENT:
            case INTERFACE:
            case EVENT:
            case DOCUMENTATION:
            case LAYOUTS:
            case NAMESPACE:
            case THEME:
                qualifiedNamePattern = "%s://%s:%s";
                break;
            case ACTION:
            case DESCRIPTION:
            case EXAMPLE:
                // TODO: FIXME
                throw new AuraRuntimeException(String.format("Find on %s defs not supported.", matcher.getDefType().name()));
            }
            rLock.lock();
            try {
            for (String namespace : delegateRegistries.getAllNamespaces()) {
                String qualifiedName = String.format(qualifiedNamePattern,
                        matcher.getPrefix() != null ? matcher.getPrefix() : "*", namespace,
                        matcher.getName() != null ? matcher.getName() : "*");
                @SuppressWarnings("unchecked")
                DefDescriptor<D> namespacedMatcher = (DefDescriptor<D>) DefDescriptorImpl.getInstance(qualifiedName,
                        matcher.getDefType().getPrimaryInterface());
                DefRegistry<D> registry = getRegistryFor(namespacedMatcher);
                if (registry != null) {
                    matched.addAll(registry.find(namespacedMatcher));
                }
            }
            } finally {
                rLock.unlock();
            }
        } else {
            matched = getRegistryFor(matcher).find(matcher);
        }
        if (localDescs != null) {
            DescriptorFilter filter = new DescriptorFilter(matcher.getQualifiedName());
            for (DefDescriptor<? extends Definition> desc : localDescs) {
                if (filter.matchDescriptor(desc)) {
                    @SuppressWarnings("unchecked")
                    DefDescriptor<D> localDesc = (DefDescriptor<D>) desc;
                    matched.add(localDesc);
                }
            }
        }
        return matched;
    }

    /**
     * A compiling definition.
     * 
     * This embodies a definition that is in the process of being compiled. It stores the descriptor, definition, and
     * the registry to which it belongs to avoid repeated lookups.
     */
    private static class CompilingDef<T extends Definition> implements Comparable<CompilingDef<?>> {
        /**
         * The descriptor we are compiling.
         */
        public DefDescriptor<T> descriptor;

        /**
         * The compiled def.
         * 
         * Should be non-null by the end of compile.
         */
        public T def;

        /**
         * All of the parents (needed in the case that we fail).
         */
        public Map<DefDescriptor<?>, CompilingDef<?>> parents = Maps.newLinkedHashMap();

        /**
         * Did we build this definition?.
         * 
         * If this is true, we need to do the validation steps after finishing.
         */
        public boolean built = false;

        /**
         * The 'level' of this def in the compile tree.
         */
        public int level = 0;

        /**
         * Is this def cacheable?
         */
        public boolean cacheable = false;


        /**
         * have we validated this def yet?
         */
        public boolean validated = false;

        private boolean inCycle = false;

        /**
         * Check to see if this def is in a cycle.
         */
        public boolean isInCycle() {
            if (inCycle) {
                return true;
            }
            Map<DefDescriptor<?>, CompilingDef<?>> cycleCheck = Maps.newHashMap();
            Map<DefDescriptor<?>, CompilingDef<?>> current = Maps.newHashMap(parents);
            Map<DefDescriptor<?>, CompilingDef<?>> next = Maps.newHashMap();
            int cycleSize;

            do {
                cycleSize = cycleCheck.size();
                for (CompilingDef<?> pcd : current.values()) {
                    next.putAll(pcd.parents);
                }
                cycleCheck.putAll(next);
                Map<DefDescriptor<?>, CompilingDef<?>> tmp = current;
                current = next;
                next = tmp;
                next.clear();
            } while (!cycleCheck.containsKey(descriptor) && cycleSize > cycleCheck.size());
            inCycle = cycleCheck.containsKey(descriptor);
            return inCycle;
        }

        @Override
        public String toString() {
            StringBuffer sb = new StringBuffer();

            sb.append(descriptor);
            if (def != null) {
                sb.append("[");
                sb.append(def.getOwnHash());
                sb.append("]");

                sb.append("<");
                sb.append(level);
                sb.append(">");
            } else {
                sb.append("[not-compiled]");
            }
            sb.append(" : built=");
            sb.append(built);
            sb.append(", cacheable=");
            sb.append(cacheable);
            sb.append(", inCycle=");
            sb.append(inCycle);
            if (parents != null && !parents.isEmpty()) {
                sb.append(", parents=");
                sb.append(parents.keySet());
            }
            return sb.toString();
        }

        @Override
        public int compareTo(CompilingDef<?> o) {
            if (o.level != this.level) {
                return o.level - this.level;
            }
            return this.descriptor.compareTo(o.descriptor);
        }
    }

    private static class CCStackEntry {
        public final DefDescriptor<?> descriptor;
        private Set<DefDescriptor<?>> next;
        private Set<DefDescriptor<?>> current;
        public final int level;

        public CCStackEntry(DefDescriptor<?> descriptor, int level) {
            this.descriptor = descriptor;
            this.next = Sets.newHashSet();
            this.current = Sets.newHashSet();
            this.level = level;
        }

        public Set<DefDescriptor<?>> getNext() {
            return this.next;
        }

        public Set<DefDescriptor<?>> swap() {
            Set<DefDescriptor<?>> tmp = current;
            this.current = this.next;
            this.next = tmp;
            tmp.clear();
            return this.current;
        }

        @Override
        public String toString() {
            return descriptor.toString()+"@"+level+"["+next.size()+","+current.size()+"]";
        }
    };

    /**
     * The compile context.
     * 
     * FIXME: the AuraContext is only needed for 'setNamepace()'.
     * 
     * This class holds the local information necessary for compilation.
     */
    private static class CompileContext {
        public final AuraContext context = Aura.getContextService().getCurrentContext();
        public final LoggingService loggingService = Aura.getLoggingService();
        public final Map<DefDescriptor<? extends Definition>, CompilingDef<?>> compiled = Maps.newHashMap();
        public final List<ClientLibraryDef> clientLibs;
        public final DefDescriptor<? extends Definition> topLevel;
        private Deque<CCStackEntry> stack = new ArrayDeque<CCStackEntry>(); 
        public int level;

        // TODO: remove preloads
        public boolean addedPreloads = false;

        public CompileContext(DefDescriptor<? extends Definition> topLevel, List<ClientLibraryDef> clientLibs) {
            this.clientLibs = clientLibs;
            this.topLevel = topLevel;
            this.level = 0;
        }

        public CompileContext(DefDescriptor<? extends Definition> topLevel) {
            this.clientLibs = null;
            this.topLevel = topLevel;
        }

        public void pushDescriptor(DefDescriptor<?> newTop) {
            stack.addLast(new CCStackEntry(newTop, level));
        }

        public Set<DefDescriptor<?>> getNext() {
            return stack.peekLast().getNext();
        }

        public Set<DefDescriptor<?>> swap() {
            return stack.peekLast().swap();
        }

        public void popDescriptor() {
            stack.removeLast();
            CCStackEntry last = stack.peekLast();
            if (last != null) {
                this.level = last.level;
            }
        }

        public <D extends Definition> CompilingDef<D> getCompiling(DefDescriptor<D> descriptor) {
            @SuppressWarnings("unchecked")
            CompilingDef<D> cd = (CompilingDef<D>) compiled.get(descriptor);
            if (cd == null) {
                cd = new CompilingDef<D>();
                compiled.put(descriptor, cd);
            }
            cd.descriptor = descriptor;
            return cd;
        }
    }

    /**
     * Fill a compiling def for a descriptor.
     * 
     * This makes sure that we can get a registry for a given def, then tries to get the def from the global cache, if
     * that fails, it retrieves from the registry, and marks the def as locally built.
     * 
     * @param compiling the current compiling def (if there is one).
     * @throws QuickFixException if validateDefinition caused a quickfix.
     */
    private <D extends Definition> boolean fillCompilingDef(CompilingDef<D> compiling, AuraContext context)
            throws QuickFixException {
        assert compiling.def == null;
        {
            //
            // First, check our local cached defs to see if we have a fully compiled version.
            // in this case, we don't care about caching, since we are done.
            //
            @SuppressWarnings("unchecked")
            D localDef = (D) defs.get(compiling.descriptor);
            if (localDef != null) {
                compiling.def = localDef;
                compiling.built = !localDef.isValid();
                if (compiling.built) {
                    localDef.validateDefinition();
                }
                return true;
            }
        }

        //
        // If there is no local cache, we must first check to see if there is a registry, as we may not have
        // a registry (depending on configuration). In the case that we don't find one, we are done here.
        //
        DefRegistry<D> registry = getRegistryFor(compiling.descriptor);
        if (registry == null) {
            defs.put(compiling.descriptor, null);
            return false;
        }

        //
        // Now, check if we can cache the def later, as we won't have the registry to check at a later time.
        // If we can cache, look it up in the cache. If we find it, we have a built definition.
        //
        if (isCacheable(registry)) {
            compiling.cacheable = true;

            @SuppressWarnings("unchecked")
            Optional<D> opt = (Optional<D>) defsCache.getIfPresent(compiling.descriptor);
            if (opt != null) {
                D cachedDef = opt.orNull();

                if (cachedDef != null) {
                    @SuppressWarnings("unchecked")
                    DefDescriptor<D> canonical = (DefDescriptor<D>) cachedDef.getDescriptor();

                    compiling.def = cachedDef;
                    compiling.descriptor = canonical;
                    compiling.built = false;
                    return true;
                } else {
                    return false;
                }
            }
        }

        //
        // The last case. This is our first compile or the def is uncacheable.
        // In this case, we make sure that the initial validation is called, and put
        // the def in the 'built' set.
        //
        compiling.def = registry.getDef(compiling.descriptor);
        if (compiling.def == null) {
            return false;
        }
        @SuppressWarnings("unchecked")
        DefDescriptor<D> canonical = (DefDescriptor<D>) compiling.def.getDescriptor();
        compiling.descriptor = canonical;

        //cc.loggingService.incrementNum(LoggingService.DEF_COUNT);
        context.setCurrentNamespace(canonical.getNamespace());
        compiling.def.validateDefinition();
        compiling.built = true;
        return true;
    }

    /**
     * Get a definition not found exception for a compiling def.
     */
    private QuickFixException getDNF(CompilingDef<?> cd) {
        //
        // If we can figure out a location, feed it back to the user, as
        // it makes it much easier to fix.
        //
        if (!cd.parents.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            Location handy = null;
            for (CompilingDef<?> parent : cd.parents.values()) {
                handy = parent.def.getLocation();
                if (sb.length() != 0) {
                    sb.append(", ");
                }
                sb.append(parent.descriptor.toString());
            }
            return new DefinitionNotFoundException(cd.descriptor, handy, sb.toString());
        }
        return new DefinitionNotFoundException(cd.descriptor);
    }

    /**
     * A private helper routine to make the compiler code more sane.
     * 
     * This processes a single definition in a dependency tree. It works as a single step in a breadth first traversal
     * of the tree, accumulating children in the 'deps' set, and updating the compile context with the current
     * definition.
     * 
     * Note that once the definition has been retrieved, this code uses the 'canonical' descriptor from the definition,
     * discarding the incoming descriptor.
     * 
     * @param descriptor the descriptor that we are currently handling, must not be in the compiling defs.
     * @param cc the compile context to allow us to accumulate information.
     * @param level the 'level' that we are compiling at.
     * @throws QuickFixException if the definition is not found, or validateDefinition() throws one.
     */
    private <D extends Definition> D getHelper(DefDescriptor<D> descriptor, CompileContext cc)
            throws QuickFixException {
        CompilingDef<D> cd = cc.getCompiling(descriptor);
        if (cc.level > cd.level) {
            cd.level = cc.level;
        }
        //
        // careful here. We don't just return with the non-null def because that breaks our levels.
        // We need to walk the whole tree, which is unfortunate perf-wise.
        //
        if (cd.def == null) {
            if (!fillCompilingDef(cd, cc.context)) {
                // No def. Blow up.
                throw getDNF(cd);
            }
            // get client libs
            if (cc.clientLibs != null && cd.def instanceof BaseComponentDef) {
                BaseComponentDef baseComponent = (BaseComponentDef) cd.def;
                baseComponent.addClientLibs(cc.clientLibs);
            }
        }

        //
        // Ok. We have a def. let's figure out what to do with it.
        // Unfortunately, we need a new set of dependencies to create the parent set below.
        // If we do not track parents, we can just use 'appendDependencies(next)', which would
        // be a significant perf advantage.
        //
        Set<DefDescriptor<?>> newDeps = Sets.newHashSet();
        cd.def.appendDependencies(newDeps);


        //
        // TODO: remove preloads
        // This pulls in the context preloads. not pretty, but it works.
        //
        if (!cc.addedPreloads && cd.descriptor.getDefType().equals(DefType.APPLICATION)) {
            cc.addedPreloads = true;
            Set<String> preloads = cc.context.getPreloads();
            for (String preload : preloads) {
                if (!preload.contains("_")) {
                    DependencyDefImpl.Builder ddb = new DependencyDefImpl.Builder();
                    ddb.setResource(preload);
                    ddb.setType("APPLICATION,COMPONENT,STYLE,EVENT");
                    ddb.build().appendDependencies(newDeps);
                }
            }
        }

        Set<DefDescriptor<?>> next = cc.getNext();
        for (DefDescriptor<?> dep : newDeps) {
            CompilingDef<?> depcd = cc.getCompiling(dep);
            if (!depcd.parents.containsKey(cd.descriptor)) {
                //
                // In this case, this is a new link, so we know that we don't
                // need to worry about cycles.
                //
                depcd.parents.put(cd.descriptor, cd);
                next.add(dep);
            } else if (!depcd.isInCycle()) {
                //
                // If this dependency is not in a cycle, continue processing.
                //
                next.add(dep);
            }
        }

        return cd.def;
    }

    /**
     * finish up the validation of a set of compiling defs.
     * 
     * @param context only needed to do setCurrentNamspace.
     */
    private void finishValidation() throws QuickFixException {
        int iteration = 0;
        List<CompilingDef<?>> compiling = null;

        //
        // Validate our references. This part is uh, painful.
        // Turns out that validating references can pull in things we didn't see, so we
        // loop infinitely... or at least a few times.
        //
        // This can be changed once we remove the ability to nest, as we will never allow
        // this. That way we won't have to copy our list so many times.
        //
        do {
            compiling = Lists.newArrayList(currentCC.compiled.values());
            for (CompilingDef<?> cd : compiling) {
                // FIXME: setting the current namespace on the context seems extremely hackish
                currentCC.context.setCurrentNamespace(cd.descriptor.getNamespace());
                if (cd.built && !cd.validated) {
                    if (iteration != 0) {
                        logger.warn("Nested add of "+cd.descriptor+" during validation of "+currentCC.topLevel);
                        //throw new AuraRuntimeException("Nested add of "+cd.descriptor+" during validation of "+currentCC.topLevel);
                    }
                    cd.def.validateReferences();
                    cd.validated = true;
                }
            }
            iteration += 1;
        } while (compiling.size() < currentCC.compiled.size());

        //
        // And finally, mark everything as happily compiled.
        //
        for (CompilingDef<?> cd : compiling) {
            if (cd.def == null) {
                throw new AuraRuntimeException("Missing def for "+cd.descriptor+" during validation of "+currentCC.topLevel);
            }
            if (cd.def != null) {
                defs.put(cd.descriptor, cd.def);
                if (cd.built) {
                    if (cd.cacheable) {
                        defsCache.put(cd.descriptor, Optional.of(cd.def));
                    }
                    cd.def.markValid();
                }
            }
        }
    }

    /**
     * Compile a single definition, finding all of the static dependencies.
     * 
     * This is the primary entry point for compiling a single definition. The basic guarantees enforced here are:
     * <ol>
     * <li>Each definition has 'validateDefinition()' called on it exactly once.</li>
     * <li>No definition is marked as valid until all definitions in the dependency set have been validated</li>
     * <li>Each definition has 'validateReferences()' called on it exactly once, after the definitions have been put in
     * local cache</li>
     * <li>All definitions are marked valid by the DefRegistry after the validation is complete</li>
     * <li>No definition should be available to other threads until it is marked valid</li>
     * <ol>
     * 
     * In order to do all of this, we keep a set of 'compiling' definitions locally, and use that to calculate
     * dependencies and walk the tree. Circular dependencies are handled gracefully, and no other thread can interfere
     * because everything is local.
     * 
     * FIXME: this should really cache invalid definitions and make sure that we don't bother re-compiling until there
     * is some change of state. However, that is rather more complex than it sounds.... and shouldn't really manifest
     * much in a released system.
     * 
     * @param descriptor the descriptor that we wish to compile.
     */
    private <D extends Definition> D compileDef(DefDescriptor<D> descriptor, CompileContext cc)
            throws QuickFixException {
        D def;
        int level = cc.level;
        boolean nested = (cc == currentCC);

        if (!nested && currentCC != null) {
            throw new AuraRuntimeException("Unexpected nesting of contexts. This is not allowed");
        }
        rLock.lock();
        try {
            currentCC = cc;
            currentCC.pushDescriptor(descriptor);
            if (!nested) {
                currentCC.loggingService.startTimer(LoggingService.TIMER_DEFINITION_CREATION);
            }
            try {
                try {
                    def = getHelper(descriptor, currentCC);
                } catch (DefinitionNotFoundException ndfe) {
                    if (nested) {
                        // ooh, nasty, we might be in a 'failure is ok state', in which case
                        // we need to be sure that we don't mess up the finishValidation step
                        // by leaving an empty entry around... If failure is _not_ ok, the next
                        // level up will break.
                        if (currentCC.compiled.containsKey(descriptor)) {
                            currentCC.compiled.remove(descriptor);
                        }
                    }
                    //
                    // ignore a nonexistent def here.
                    //
                    return null;
                }
                //
                // This loop accumulates over a breadth first traversal of the dependency tree.
                // All child definitions are added to the 'next' set, while walking the 'current'
                // set.
                //
                while (currentCC.getNext().size() > 0) {
                    Set<DefDescriptor<?>> current = currentCC.swap();

                    level += 1;
                    if (level > 1000) {
                        throw new AuraRuntimeException("too many levels, you have a cycle");
                    }
                    currentCC.level = level;
                    for (DefDescriptor<?> cdesc : current) {
                        getHelper(cdesc, currentCC);
                    }
                }
                if (!nested) {
                    finishValidation();
                }
                return def;
            } finally {
                if (!nested) {
                    currentCC.loggingService.stopTimer(LoggingService.TIMER_DEFINITION_CREATION);
                }
                currentCC.popDescriptor();
            }
        } finally {
            if (!nested) {
                currentCC = null;
            }
            rLock.unlock();
        }
    }

    /**
     * Internal routine to compile and return a DependencyEntry.
     * 
     * This routine always compiles the definition, even if it is in the caches. If the incoming descriptor does not
     * correspond to a definition, it will return null, otherwise, on failure it will throw a QuickFixException.
     * 
     * Please look at {@link #localDependencies} if you are mucking in here.
     * 
     * Side Effects:
     * <ul>
     * <li>All definitions that were encountered during the compile will be put in the local def cache, even if a QFE is
     * thrown</li>
     * <li>A hash is compiled for the definition if it compiles</li>
     * <li>a dependency entry is cached locally in any case</li>
     * <li>a dependency entry is cached globally if the definition compiled</li>
     * </ul>
     * 
     * @param descriptor the incoming descriptor to compile
     * @return the definition compiled from the descriptor, or null if not found.currentCC
     * @throws QuickFixException if the definition failed to compile.
     */
    protected <T extends Definition> DependencyEntry compileDE(DefDescriptor<T> descriptor) throws QuickFixException {
        // See localDependencies commentcurrentCC
        String key = makeLocalKey(descriptor);

        if (currentCC != null) {
            throw new AuraRuntimeException("Ugh, nested compileDE/buildDE on "+currentCC.topLevel
                    +" trying to build "+descriptor);
        }
        //
        // This is very ugly... it probably should not be done this way, but AFAICT we need to do this
        // unfortunately, it also will break if anyone ever changes what happens. Our real problem is
        // the roundabout routing and uncertainty therein. Going out to DefinitionService to get defs, then
        // coming back through here is dangerous at best.
        //
        DefDescriptor<? extends BaseComponentDef> rootDesc;
       
        rootDesc = Aura.getContextService().getCurrentContext().getApplicationDescriptor();
        if (!descriptor.equals(rootDesc) && rootDesc != null) {
            //
            // This is needed to make sure that we have already loaded up all our definitions,
            // and don't need to re-fetch this half way through.
            //
            try {
                getDef(rootDesc);
            } catch (QuickFixException qfe) {
                // ignore this, we'll hit it later anyway.
            }
        }

        try {
            List<ClientLibraryDef> clientLibs = Lists.newArrayList();
            CompileContext cc = new CompileContext(descriptor, clientLibs);
            Definition def = compileDef(descriptor, cc);
            DependencyEntry de;
            String uid;
            long lmt = 0;

            if (def == null) {
                return null;
            }

            List<CompilingDef<?>> compiled = Lists.newArrayList(cc.compiled.values());
            Collections.sort(compiled);

            Set<DefDescriptor<? extends Definition>> deps = Sets.newLinkedHashSet();

            //
            // Now walk the sorted list, building up our dependencies, uid, and lmt.
            //
            StringBuilder sb = new StringBuilder(256);
            Hash.StringBuilder globalBuilder = new Hash.StringBuilder();
            for (CompilingDef<?> cd : compiled) {
                if (cd.def == null) {
                    // actually, this should never happen.
                    throw getDNF(cd);
                }

                deps.add(cd.descriptor);
                lmt = updateLastMod(lmt, cd.def);

                //
                // Now update our hash.
                //
                sb.setLength(0);
                sb.append(cd.descriptor.getQualifiedName().toLowerCase());
                sb.append("|");
                String hash = cd.def.getOwnHash();
                if (hash != null) {
                    sb.append(hash.toString());
                }
                sb.append(",");
                globalBuilder.addString(sb.toString());
            }
            lmt = updateLastMod(lmt, def);
            uid = globalBuilder.build().toString();

            //
            // Now try a re-lookup. This may catch existing cached
            // entries where uid was null.
            //
            // TODO : this breaks last mod time tests, as it causes the mod time
            // to stay at the first compile time. We should phase out last mod
            // time, and then re-instantiate this code.
            //
            // de = getDE(uid, key);
            // if (de == null) {

            de = new DependencyEntry(uid, Collections.unmodifiableSet(deps), lmt, clientLibs);
            depsCache.put(makeGlobalKey(de.uid, descriptor), de);

            // See localDependencies comment
            localDependencies.put(de.uid, de);
            localDependencies.put(key, de);
            // }
            return de;
        } catch (QuickFixException qfe) {
            // See localDependencies comment
            localDependencies.put(key, new DependencyEntry(qfe));
            throw qfe;
        }
    }

    private long updateLastMod(long lastModTime, Definition def) {
        if (def.getLocation() != null && def.getLocation().getLastModified() > lastModTime) {
            lastModTime = def.getLocation().getLastModified();
        }
        return lastModTime;
    }

    /**
     * Get a dependency entry for a given uid.
     * 
     * This is a convenience routine to check both the local and global cache for a value.
     * 
     * Please look at {@link #localDependencies} if you are mucking in here.
     * 
     * Side Effects:
     * <ul>
     * <li>If a dependency is found in the global cache, it is populated into the local cache.</li>
     * </ul>
     * 
     * @param uid the uid may be null, if so, it only checks the local cache.
     * @param descriptor the descriptor, used for both global and local cache lookups.
     * @return the DependencyEntry or null if none present.
     */
    private DependencyEntry getDE(String uid, DefDescriptor<?> descriptor) {
        // See localDependencies comment
        String key = makeLocalKey(descriptor);
        DependencyEntry de;

        if (uid != null) {
            de = localDependencies.get(uid);
            if (de != null) {
                return de;
            }
            de = depsCache.getIfPresent(makeGlobalKey(uid, descriptor));
        } else {
            // See localDependencies comment
            de = localDependencies.get(key);
            if (de != null) {
                return de;
            }
            de = depsCache.getIfPresent(key);
        }
        if (de != null) {
            // See localDependencies comment
            localDependencies.put(de.uid, de);
            localDependencies.put(key, de);
        }
        return de;
    }

    @Override
    public long getLastMod(String uid) {
        DependencyEntry de = localDependencies.get(uid);

        if (de != null) {
            return de.lastModTime;
        }
        return 0;
    }

    @Override
    public Set<DefDescriptor<?>> getDependencies(String uid) {
        DependencyEntry de = localDependencies.get(uid);

        if (de != null) {
            return de.dependencies;
        }
        return null;
    }

    @Override
    public List<ClientLibraryDef> getClientLibraries(String uid) {
        DependencyEntry de = localDependencies.get(uid);

        if (de != null) {
            return de.clientLibraries;
        }
        return null;
    }

    /**
     * Typesafe helper for getDef.
     * 
     * This adds new definitions (unvalidated) to the list passed in. Definitions that were previously built are simply
     * added to the local cache.
     * 
     * The quick fix exception case is actually a race condition where we previously had a set of depenendencies, and
     * something changed, making our set inconsistent. There are no guarantees that during a change all MDRs will have a
     * correct set of definitions.
     * 
     * @param context The aura context for the compiling def.
     * @param descriptor the descriptor for which we need a definition.
     * @return A compilingDef for the definition, or null if not needed.
     * @throws QuickFixException if something has gone terribly wrong.
     */
    private <D extends Definition> void validateHelper(DefDescriptor<D> descriptor) throws QuickFixException {
        CompilingDef<D> compiling = new CompilingDef<D>();
        compiling.descriptor = descriptor;
        currentCC.compiled.put(descriptor, compiling);
    }

    /**
     * Build a DE 'in place' with no tree traversal.
     */
    private <D extends Definition> void buildDE(DependencyEntry de, DefDescriptor<?> descriptor)
            throws QuickFixException {
        if (currentCC != null) {
            throw new AuraRuntimeException("Ugh, nested compileDE/buildDE on "+currentCC.topLevel
                    +" trying to build "+descriptor);
        }
        currentCC = new CompileContext(descriptor);
        try {
            validateHelper(descriptor);
            for (DefDescriptor<?> dd : de.dependencies) {
                validateHelper(dd);
            }
            for (CompilingDef<?> compiling : currentCC.compiled.values()) {
                if (!fillCompilingDef(compiling, currentCC.context)) {
                    throw new DefinitionNotFoundException(descriptor);
                }
            }
            finishValidation();
        } finally {
            currentCC = null;
        }
    }


    /**
     * Get a definition.
     * 
     * This does a scan of the loaded dependency entries to check if there is something to pull, otherwise, it just
     * compiles the entry. This should log a warning somewhere, as it is a dependency that was not noted.
     * 
     * @param descriptor the descriptor to find.
     * @return the corresponding definition, or null if it doesn't exist.
     * @throws QuickFixException if there is a compile time error.
     */
    @Override
    public <D extends Definition> D getDef(DefDescriptor<D> descriptor) throws QuickFixException {
        if (descriptor == null) {
            return null;
        }
        rLock.lock();
        try {
            //
            // If our current context is not null, we always want to recurse
            // in to properly include the defs.
            //
            if (currentCC != null) {
                if (currentCC.compiled.containsKey(descriptor)) {
                    @SuppressWarnings("unchecked")
                    CompilingDef<D> cd = (CompilingDef<D>)currentCC.compiled.get(descriptor);
                    if (cd.def != null) {
                        return cd.def;
                    }
                }
                //
                // If we are nested, compileDef will do the right thing.
                // This is a bit ugly though.
                //
                return compileDef(descriptor, currentCC);
            }
            if (defs.containsKey(descriptor)) {
                @SuppressWarnings("unchecked")
                D def = (D) defs.get(descriptor);
                return def;
            }
            DependencyEntry de = getDE(null, descriptor);
            if (de == null) {
                for (DependencyEntry det : localDependencies.values()) {
                    if (det.dependencies != null && det.dependencies.contains(descriptor)) {
                        de = det;
                        break;
                    }
                }
                if (de == null) {
                    compileDE(descriptor);
                    @SuppressWarnings("unchecked")
                    D def = (D) defs.get(descriptor);
                    return def;
                }
            }
            //
            // found an entry.
            // In this case, throw a QFE if we have one.
            //
            if (de.qfe != null) {
                throw de.qfe;
            }
            //
            // Now we need to actually do the build..
            //
            buildDE(de, descriptor);
            @SuppressWarnings("unchecked")
            D def = (D) defs.get(descriptor);
            return def;
        } finally {
            rLock.unlock();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <D extends Definition> void save(D def) {
        wLock.lock();
        try {
        getRegistryFor((DefDescriptor<D>) def.getDescriptor()).save(def);
        invalidate(def.getDescriptor());
        } finally {
            wLock.unlock();
    }
    }

    @Override
    public <D extends Definition> boolean exists(DefDescriptor<D> descriptor) {
        boolean cacheable;
        boolean regExists;

        rLock.lock();
        try {
        if (defs.get(descriptor) != null) {
            return true;
        }
        DefRegistry<D> reg = getRegistryFor(descriptor);
        if (reg == null) {
            return false;
        }
        cacheable = isCacheable(reg);
        if (cacheable) {
            //
            // Try our various caches.
            //
            Boolean val = existsCache.getIfPresent(descriptor);
            if (val != null && val.booleanValue()) {
                return true;
            }
            Optional<?> opt = defsCache.getIfPresent(descriptor);
            if (opt != null) {
                //
                // We cache here.
                //
                if (opt.isPresent()) {
                    existsCache.put(descriptor, Boolean.TRUE);
                    return true;
                } else {
                    existsCache.put(descriptor, Boolean.FALSE);
                    return false;
                }
            }
        }
            regExists = reg.exists(descriptor);
        if (cacheable) {
            Boolean cacheVal = Boolean.valueOf(regExists);
            existsCache.put(descriptor, cacheVal);
        }
        } finally {
            rLock.unlock();
        }
        return regExists;
    }

    /**
     * This figures out based on prefix what registry this component is for, it could return null if the prefix is not
     * found.
     */
    @SuppressWarnings("unchecked")
    private <T extends Definition> DefRegistry<T> getRegistryFor(DefDescriptor<T> descriptor) {
        return (DefRegistry<T>) delegateRegistries.getRegistryFor(descriptor);
    }

    @Override
    public <D extends Definition> void addLocalDef(D def) {
        DefDescriptor<? extends Definition> desc = def.getDescriptor();

        defs.put(desc, def);
        if (localDescs == null) {
            localDescs = Sets.newHashSet();
        }
        localDescs.add(desc);
    }

    @Override
    public <T extends Definition> Source<T> getSource(DefDescriptor<T> descriptor) {
        DefRegistry<T> reg = getRegistryFor(descriptor);
        if (reg != null) {
            return reg.getSource(descriptor);
        }
        return null;
    }

    @Override
    public boolean namespaceExists(String ns) {
        return delegateRegistries.getAllNamespaces().contains(ns);
    }

    /**
     * Get a security provider for the application.
     * 
     * This should probably catch the quick fix exception and simply treat it as a null security provider. This caches
     * the security provider.
     * 
     * @return the sucurity provider for the application or null if none.
     * @throws QuickFixException if there was a problem compiling.
     */
    private SecurityProviderDef getSecurityProvider() throws QuickFixException {
        DefDescriptor<? extends BaseComponentDef> rootDesc = Aura.getContextService().getCurrentContext()
                .getApplicationDescriptor();
        if (securityProvider == null || !lastRootDesc.equals(rootDesc)) {
            SecurityProviderDef securityProviderDef = null;
            if (rootDesc != null && rootDesc.getDefType().equals(DefType.APPLICATION)) {
                ApplicationDef root = null;
                try {
                    root = (ApplicationDef) getDef(rootDesc);
                } catch (QuickFixException qfe) {
                    // ignore, we get null
                }
                if (root != null) {
                    DefDescriptor<SecurityProviderDef> securityDesc = root.getSecurityProviderDefDescriptor();
                    if (securityDesc != null) {
                        securityProviderDef = getDef(securityDesc);
                    }
                }
            }
            
            securityProvider = securityProviderDef;
            lastRootDesc = rootDesc;
        }
        
        return securityProvider;
    }

    @Override
    public void assertAccess(DefDescriptor<?> desc) throws QuickFixException {
        rLock.lock();
        try {
        if (!accessCache.contains(desc)) {
            Aura.getLoggingService().incrementNum("SecurityProviderCheck");
            DefType defType = desc.getDefType();
            String ns = desc.getNamespace();
            AuraContext context = Aura.getContextService().getCurrentContext();
            Mode mode = context.getMode();
            String prefix = desc.getPrefix();
            //
            // This breaks encapsulation! -gordon
            //
            boolean isTopLevel = desc.equals(context.getApplicationDescriptor());

            if (isTopLevel) {
                //
                // If we are trying to access the top level component, we need to ensure
                // that it is _not_ abstract.
                //
                BaseComponentDef def = getDef(context.getApplicationDescriptor());
                if (def != null && def.isAbstract() && def.getProviderDescriptor() == null) {
                    throw new NoAccessException(String.format("Access to %s disallowed. Abstract definition.", desc));
                }
            }
            //
            // If this is _not_ the top level, we allow circumventing the security provider.
            // This means that certain things will short-circuit, hopefully making checks faster...
            // Not sure if this is premature optimization or not.
            //
            if (!isTopLevel || desc.getDefType().equals(DefType.COMPONENT)) {
                if (!securedDefTypes.contains(defType)
                        || unsecuredPrefixes.contains(prefix)
                        || unsecuredNamespaces.contains(ns)
                        || (mode != Mode.PROD && (!Aura.getConfigAdapter().isProduction()) && unsecuredNonProductionNamespaces
                                .contains(ns))) {
                    accessCache.add(desc);
                    return;
                }

                if (ns != null && DefDescriptor.JAVA_PREFIX.equals(prefix)) {
                    // handle java packages that have namespaces like aura.impl.blah
                    for (String okNs : unsecuredNamespaces) {
                        if (ns.startsWith(okNs)) {
                            accessCache.add(desc);
                            return;
                        }
                    }
                }
            }

            SecurityProviderDef securityProviderDef = getSecurityProvider();
            if (securityProviderDef == null) {
                if (mode != Mode.PROD && !Aura.getConfigAdapter().isProduction()) {
                    accessCache.add(desc);
                    return;
                } else {
                    throw new NoAccessException(String.format("Access to %s disallowed.  No Security Provider found.",
                            desc));
                }
            } else {
                try {
                    if (!securityProviderDef.isAllowed(desc)) {
                        throw new NoAccessException(String.format("Access to %s disallowed by %s", desc,
                                securityProviderDef.getDescriptor().getName()));
                    }
                } catch (NoAccessException e) {
                    // Sometimes security providers throw instead of returning.  Rather than losing
                    // the stack trace in the exception, we catch and re-throw with that information
                    throw e;
                }
            }
            accessCache.add(desc);
        }
        } finally {
            rLock.unlock();
        }
    }

    /**
     * only used by admin tools to view all registries
     */
    public DefRegistry<?>[] getAllRegistries() {
        return delegateRegistries.getAllRegistries();
    }

    /**
     * Filter the entire set of current definitions by a set of preloads.
     * 
     * This filtering is very simple, it just looks for local definitions that are not included in the preload set.
     */
    @Override
    public Map<DefDescriptor<? extends Definition>, Definition> filterRegistry(Set<DefDescriptor<?>> preloads) {
        Map<DefDescriptor<? extends Definition>, Definition> filtered;

        if (preloads == null || preloads.isEmpty()) {
            return Maps.newHashMap(defs);
        }
        filtered = Maps.newHashMapWithExpectedSize(defs.size());
        for (Map.Entry<DefDescriptor<? extends Definition>, Definition> entry : defs.entrySet()) {
            if (!preloads.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    @Override
    public <T extends Definition> boolean invalidate(DefDescriptor<T> descriptor) {
        defs.clear();
        if (localDescs != null) {
            localDescs.clear();
        }
        localDependencies.clear();
        accessCache.clear();
        securityProvider = null;
        lastRootDesc = null;
        depsCache.invalidateAll();
        defsCache.invalidateAll();
        existsCache.invalidateAll();
        descriptorFilterCache.invalidateAll();
        return false;
    }

    private String getKey(DependencyEntry de, DefDescriptor<?> descriptor, String key) {
        return String.format("%s@%s@%s", de.uid, descriptor.getQualifiedName().toLowerCase(), key);
    }

    @Override
    public String getCachedString(String uid, DefDescriptor<?> descriptor, String key) {
        DependencyEntry de = localDependencies.get(uid);

        if (de != null) {
            return stringsCache.getIfPresent(getKey(de, descriptor, key));
        }
        return null;
    }

    @Override
    public void putCachedString(String uid, DefDescriptor<?> descriptor, String key, String value) {
        DependencyEntry de = localDependencies.get(uid);

        if (de != null) {
            stringsCache.put(getKey(de, descriptor, key), value);
        }
    }

    /**
     * Get the UID.
     * 
     * This uses some trickery to try to be efficient, including using a dual keyed local cache to avoid looking up
     * values more than once even in the absense of remembered context.
     * 
     * Note: there is no guarantee that the definitions have been fetched from cache here, so there is a very subtle
     * race condition.
     * 
     * Also note that this _MUST NOT_ be called inside of a compile, or things may get out of wack. We probably
     * should be asserting this somewhere.
     * 
     * @param uid the uid for cache lookup (null means unknown).
     * @param descriptor the descriptor to fetch.
     * @return the correct uid for the definition, or null if there is none.
     * @throws QuickFixException if the definition cannot be compiled.
     */
    @Override
    public <T extends Definition> String getUid(String uid, DefDescriptor<T> descriptor) throws QuickFixException {
        if (descriptor == null) {
            return null;
        }

        DependencyEntry de = null;

        de = getDE(uid, descriptor);
        if (de == null) {
            try {
                de = compileDE(descriptor);
                //
                // If we can't find our descriptor, we just give back a null.
                if (de == null) {
                    return null;
                }
            } catch (QuickFixException qfe) {
                // try to pick it up from the cache.
                de = getDE(null, descriptor);
                // this should never happen.
                if (de == null) {
                    throw new AuraRuntimeException("unexpected null on QFE");
                }
            }
        }
        if (de.qfe != null) {
            throw de.qfe;
        }
        return de.uid;
    }

    /** Creates a key for the localDependencies, using DefType and FQN. */
    private String makeLocalKey(DefDescriptor<?> descriptor) {
        return descriptor.getDefType().toString() + ":" + descriptor.getQualifiedName().toLowerCase();
    }

    /**
     * Creates a key for the global {@link #depsCache}, using UID, type, and FQN.
     */
    private String makeGlobalKey(String uid, DefDescriptor<?> descriptor) {
        return uid + "/" + makeLocalKey(descriptor);
    }

    /**
     * The driver for cache-consistency management in response to source changes. MDR drives the process, will notify
     * all registered listeners while write blocking, then invalidate it's own caches. If this routine can't acquire the
     * lock , it will log it as an non-fatal error, as it only results in staleness.
     * 
     * @param listeners - collections of listeners to notify of source changes
     * @param source - DefDescriptor that changed - for granular cache clear (currently not considered here, but other
     *            listeners may make use of it)
     * @param event - what type of event triggered the change
     */
    public static void notifyDependentSourceChange(Collection<WeakReference<SourceListener>> listeners,
            DefDescriptor<?> source, SourceListener.SourceMonitorEvent event, String filePath) {
        boolean haveLock = false;

        try {
            // We have now eliminated all known deadlocks, but for production safety, we never want to block forever
            haveLock = wLock.tryLock(5, TimeUnit.SECONDS);

            // If this occurs, we have a new deadlock. But it only means temporary cache staleness, so it is not fatal
            if (!haveLock) {
                logger.error("Couldn't acquire cache clear lock in a reasonable time.  Cache may be stale until next clear.");
                return;
            }

            // successfully acquired the lock, start clearing caches
            // notify provided listeners, presumably to clear caches
            for (WeakReference<SourceListener> i : listeners) {
                SourceListener sl = i.get();

                if (sl != null) {
                    sl.onSourceChanged(source, event, filePath);
                }
            }
            // lastly, clear MDR's static caches
            invalidateStaticCaches(source);

        } catch (InterruptedException e) {
        } finally {
            if (haveLock) {
                wLock.unlock();
            }
        }
    }

    private static void invalidateStaticCaches(DefDescriptor<?> descriptor) {

        depsCache.invalidateAll();
        descriptorFilterCache.invalidateAll();
        stringsCache.invalidateAll();

        if (descriptor == null) {
            defsCache.invalidateAll();
            existsCache.invalidateAll();
        } else {
            DefinitionService ds = Aura.getDefinitionService();
            DefDescriptor<ComponentDef> cdesc = ds.getDefDescriptor(descriptor, "markup", ComponentDef.class);
            DefDescriptor<ApplicationDef> adesc = ds.getDefDescriptor(descriptor, "markup", ApplicationDef.class);

            defsCache.invalidate(descriptor);
            existsCache.invalidate(descriptor);
            defsCache.invalidate(cdesc);
            existsCache.invalidate(cdesc);
            defsCache.invalidate(adesc);
            existsCache.invalidate(adesc);

            // invalidate all DDs with the same namespace if its a namespace DD
            if (descriptor.getDefType() == DefType.NAMESPACE) {
                invalidateScope(descriptor, true, false);
            }

            if (descriptor.getDefType() == DefType.LAYOUTS) {
                invalidateScope(descriptor, true, true);
            }
        }
    }

    private static void invalidateScope(DefDescriptor<?> descriptor, boolean clearNamespace, boolean clearName) {
        final ConcurrentMap<DefDescriptor<?>, Optional<? extends Definition>> defsMap = defsCache.asMap();
        final String namespace = descriptor.getNamespace();
        final String name = descriptor.getName();

        for (DefDescriptor<?> dd : defsMap.keySet()) {
            boolean sameNamespace = namespace.equals(dd.getNamespace());
            boolean sameName = name.equals(dd.getName());
            boolean shouldClear = (clearNamespace && clearName) ?
                    (clearNamespace && sameNamespace) && (clearName && sameName) :
                    (clearNamespace && sameNamespace) || (clearName && sameName);

            if (shouldClear) {
                defsCache.invalidate(dd);
                existsCache.invalidate(dd);
            }
        }
    }

    public static Collection<Optional<? extends Definition>> getCachedDefs() {
        return defsCache.asMap().values();
    }

    public static CacheStats getDefsCacheStats() {
        return defsCache.stats();
    }

    public static CacheStats getExistsCacheStats() {
        return existsCache.stats();
    }

    public static CacheStats getStringsCacheStats() {
        return stringsCache.stats();
    }

    public static CacheStats getDescriptorFilterCacheStats() {
        return descriptorFilterCache.stats();
    }
}
