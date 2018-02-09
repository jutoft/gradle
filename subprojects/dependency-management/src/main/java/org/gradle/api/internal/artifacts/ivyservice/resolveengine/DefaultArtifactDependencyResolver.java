/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.resolveengine;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.gradle.api.Action;
import org.gradle.api.artifacts.ModuleIdentifier;
import org.gradle.api.artifacts.dsl.CapabilitiesHandler;
import org.gradle.api.internal.FeaturePreviews;
import org.gradle.api.internal.artifacts.ArtifactDependencyResolver;
import org.gradle.api.internal.artifacts.ComponentSelectorConverter;
import org.gradle.api.internal.artifacts.DefaultModuleIdentifier;
import org.gradle.api.internal.artifacts.GlobalDependencyResolutionRules;
import org.gradle.api.internal.artifacts.ResolveContext;
import org.gradle.api.internal.artifacts.configurations.ConflictResolution;
import org.gradle.api.internal.artifacts.configurations.ResolutionStrategyInternal;
import org.gradle.api.internal.artifacts.dsl.dependencies.DefaultDependencyConstraintHandler;
import org.gradle.api.internal.artifacts.ivyservice.clientmodule.ClientModuleResolver;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.CachingDependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DefaultDependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.dependencysubstitution.DependencySubstitutionApplicator;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ComponentResolvers;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolveIvyFactory;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.ResolverProviderFactory;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionComparator;
import org.gradle.api.internal.artifacts.ivyservice.moduleconverter.dependencies.DependencyDescriptorFactory;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.DependencyArtifactsVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.artifact.ResolvedArtifactsGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.excludes.ModuleExclusions;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.CompositeDependencyGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.DependencyGraphVisitor;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.builder.DependencyGraphBuilder;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.CandidateModule;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.ConflictHandler;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.ConflictResolutionResult;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.DefaultConflictHandler;
import org.gradle.api.internal.artifacts.ivyservice.resolveengine.graph.conflicts.PotentialConflict;
import org.gradle.api.internal.artifacts.repositories.ResolutionAwareRepository;
import org.gradle.api.internal.artifacts.type.ArtifactTypeRegistry;
import org.gradle.api.internal.attributes.AttributesSchemaInternal;
import org.gradle.api.internal.attributes.ImmutableAttributesFactory;
import org.gradle.api.specs.Spec;
import org.gradle.internal.component.model.DependencyMetadata;
import org.gradle.internal.operations.BuildOperationExecutor;
import org.gradle.internal.resolve.resolver.ComponentMetaDataResolver;
import org.gradle.internal.resolve.resolver.DependencyToComponentIdResolver;
import org.gradle.internal.resolve.resolver.ResolveContextToComponentResolver;
import org.gradle.internal.resolve.result.BuildableComponentResolveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DefaultArtifactDependencyResolver implements ArtifactDependencyResolver {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultArtifactDependencyResolver.class);
    private final DependencyDescriptorFactory dependencyDescriptorFactory;
    private final List<ResolverProviderFactory> resolverFactories;
    private final ResolveIvyFactory ivyFactory;
    private final VersionComparator versionComparator;
    private final ModuleExclusions moduleExclusions;
    private final BuildOperationExecutor buildOperationExecutor;
    private final ComponentSelectorConverter componentSelectorConverter;
    private final FeaturePreviews featurePreviews;
    private final ImmutableAttributesFactory attributesFactory;

    public DefaultArtifactDependencyResolver(BuildOperationExecutor buildOperationExecutor, List<ResolverProviderFactory> resolverFactories, ResolveIvyFactory ivyFactory, DependencyDescriptorFactory dependencyDescriptorFactory, VersionComparator versionComparator, ModuleExclusions moduleExclusions, ComponentSelectorConverter componentSelectorConverter, FeaturePreviews featurePreviews, ImmutableAttributesFactory attributesFactory) {
        this.resolverFactories = resolverFactories;
        this.ivyFactory = ivyFactory;
        this.dependencyDescriptorFactory = dependencyDescriptorFactory;
        this.versionComparator = versionComparator;
        this.moduleExclusions = moduleExclusions;
        this.buildOperationExecutor = buildOperationExecutor;
        this.componentSelectorConverter = componentSelectorConverter;
        this.featurePreviews = featurePreviews;
        this.attributesFactory = attributesFactory;
    }

    @Override
    public void resolve(ResolveContext resolveContext, List<? extends ResolutionAwareRepository> repositories, GlobalDependencyResolutionRules metadataHandler, Spec<? super DependencyMetadata> edgeFilter, DependencyGraphVisitor graphVisitor, DependencyArtifactsVisitor artifactsVisitor, AttributesSchemaInternal consumerSchema, ArtifactTypeRegistry artifactTypeRegistry, CapabilitiesHandler capabilitiesHandler) {
        LOGGER.debug("Resolving {}", resolveContext);
        ((DefaultDependencyConstraintHandler.CapabilitiesHandlerSpike) capabilitiesHandler).convertToReplacementRules();
        ComponentResolversChain resolvers = createResolvers(resolveContext, repositories, metadataHandler, artifactTypeRegistry);
        DependencyGraphBuilder builder = createDependencyGraphBuilder(resolvers, resolveContext.getResolutionStrategy(), metadataHandler, edgeFilter, consumerSchema, moduleExclusions, buildOperationExecutor, capabilitiesHandler);

        DependencyGraphVisitor artifactsGraphVisitor = new ResolvedArtifactsGraphVisitor(artifactsVisitor, resolvers.getArtifactSelector(), moduleExclusions);

        // Resolve the dependency graph
        builder.resolve(resolveContext, new CompositeDependencyGraphVisitor(graphVisitor, artifactsGraphVisitor));
    }

    private DependencyGraphBuilder createDependencyGraphBuilder(ComponentResolversChain componentSource, ResolutionStrategyInternal resolutionStrategy, GlobalDependencyResolutionRules globalRules, Spec<? super DependencyMetadata> edgeFilter, AttributesSchemaInternal attributesSchema, ModuleExclusions moduleExclusions, BuildOperationExecutor buildOperationExecutor, CapabilitiesHandler capabilitiesHandler) {

        DependencyToComponentIdResolver componentIdResolver = componentSource.getComponentIdResolver();
        ComponentMetaDataResolver componentMetaDataResolver = new ClientModuleResolver(componentSource.getComponentResolver(), dependencyDescriptorFactory);

        ResolveContextToComponentResolver requestResolver = createResolveContextConverter();
        ConflictHandler conflictHandler = createConflictHandler(resolutionStrategy, globalRules, capabilitiesHandler);

        DependencySubstitutionApplicator applicator =
            new CachingDependencySubstitutionApplicator(new DefaultDependencySubstitutionApplicator(resolutionStrategy.getDependencySubstitutionRule()));
        return new DependencyGraphBuilder(componentIdResolver, componentMetaDataResolver, requestResolver, conflictHandler, edgeFilter, attributesSchema, moduleExclusions, buildOperationExecutor, globalRules.getModuleMetadataProcessor().getModuleReplacements(), applicator, componentSelectorConverter, featurePreviews, attributesFactory);
    }

    private ComponentResolversChain createResolvers(ResolveContext resolveContext, List<? extends ResolutionAwareRepository> repositories, GlobalDependencyResolutionRules metadataHandler, ArtifactTypeRegistry artifactTypeRegistry) {
        List<ComponentResolvers> resolvers = Lists.newArrayList();
        for (ResolverProviderFactory factory : resolverFactories) {
            if (factory.canCreate(resolveContext)) {
                resolvers.add(factory.create(resolveContext));
            }
        }
        ResolutionStrategyInternal resolutionStrategy = resolveContext.getResolutionStrategy();
        resolvers.add(ivyFactory.create(resolutionStrategy, repositories, metadataHandler.getComponentMetadataProcessor()));
        return new ComponentResolversChain(resolvers, artifactTypeRegistry);
    }

    private ResolveContextToComponentResolver createResolveContextConverter() {
        return new DefaultResolveContextToComponentResolver();
    }

    private ConflictHandler createConflictHandler(ResolutionStrategyInternal resolutionStrategy, GlobalDependencyResolutionRules metadataHandler, CapabilitiesHandler capabilitiesHandler) {
        ModuleConflictResolver conflictResolver = null;
        ConflictResolution conflictResolution = resolutionStrategy.getConflictResolution();
        switch (conflictResolution) {
            case strict:
                conflictResolver = new StrictConflictResolver();
                break;
            case latest:
                conflictResolver = new LatestModuleConflictResolver(versionComparator);
                break;
            case preferProjectModules:
                conflictResolver = new ProjectDependencyForcingResolver(new LatestModuleConflictResolver(versionComparator));
                break;
        }
        conflictResolver = new VersionSelectionReasonResolver(conflictResolver);
        DefaultConflictHandler conflictHandler = new DefaultConflictHandler(conflictResolver, metadataHandler.getModuleMetadataProcessor().getModuleReplacements());
        return new SpikeCapabilityConflictHandler((DefaultDependencyConstraintHandler.CapabilitiesHandlerSpike) capabilitiesHandler, conflictHandler);
    }

    private static class DefaultResolveContextToComponentResolver implements ResolveContextToComponentResolver {
        @Override
        public void resolve(ResolveContext resolveContext, BuildableComponentResolveResult result) {
            result.resolved(resolveContext.toRootComponentMetaData());
        }
    }

    private static class SpikeCapabilityConflictHandler implements ConflictHandler {
        private final DefaultDependencyConstraintHandler.CapabilitiesHandlerSpike capabilitiesHandler;
        private final ConflictHandler delegate;
        private final Map<String, List<ModuleIdentifier>> capabilityToModules = Maps.newHashMap();

        private SpikeCapabilityConflictHandler(DefaultDependencyConstraintHandler.CapabilitiesHandlerSpike capabilitiesHandler, ConflictHandler delegate) {
            this.capabilitiesHandler = capabilitiesHandler;
            this.delegate = delegate;
        }

        @Override
        public PotentialConflict registerModule(final CandidateModule newModule) {
            Map<String, Set<String>> conflict = capabilitiesHandler.conflictingModules(newModule.getId().getGroup() + ":" + newModule.getId().getName());
            if (!conflict.isEmpty()) {
                for (Map.Entry<String, Set<String>> entry : conflict.entrySet()) {
                    Set<String> value = entry.getValue();
                    List<ModuleIdentifier> modules = Lists.newArrayList();
                    for (String mid : value) {
                        String[] parts = mid.split(":");
                        modules.add(DefaultModuleIdentifier.newId(parts[0], parts[1]));
                    }
                    capabilityToModules.put(entry.getKey(), modules);
                }
                return new PotentialConflict() {
                    @Override
                    public void withParticipatingModules(Action<ModuleIdentifier> action) {
                        action.execute(newModule.getId());
                    }

                    @Override
                    public boolean conflictExists() {
                        return true;
                    }
                };
            }
            return delegate.registerModule(newModule);
        }

        @Override
        public boolean hasConflicts() {
            return !capabilityToModules.isEmpty() || delegate.hasConflicts();
        }

        @Override
        public void resolveNextConflict(Action<ConflictResolutionResult> resolutionAction) {
            if (!capabilityToModules.isEmpty()) {
                Iterator<Map.Entry<String, List<ModuleIdentifier>>> iterator = capabilityToModules.entrySet().iterator();
                final Map.Entry<String, List<ModuleIdentifier>> entry = iterator.next();
                iterator.remove();
                resolutionAction.execute(new ConflictResolutionResult() {
                    @Override
                    public void withParticipatingModules(Action<ModuleIdentifier> action) {
                    }

                    @Override
                    public <T extends ComponentResolutionState> T getSelected() {
                        throw new RuntimeException("Cannot choose between " + Joiner.on(" or ").join(entry.getValue()) + " because they provide the same capability: " + entry.getKey());
                    }

                    @Override
                    public Collection<? extends ComponentResolutionState> getCandidates() {
                        return null;
                    }
                });
            } else {
                delegate.resolveNextConflict(resolutionAction);
            }
        }

        @Override
        public void registerResolver(ModuleConflictResolver conflictResolver) {
            delegate.registerResolver(conflictResolver);
        }
    }

}
