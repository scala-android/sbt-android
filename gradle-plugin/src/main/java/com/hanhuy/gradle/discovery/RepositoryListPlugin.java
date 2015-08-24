package com.hanhuy.gradle.discovery;

import javax.inject.Inject;

import org.gradle.api.Action;
import org.gradle.api.*;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.artifacts.repositories.PasswordCredentials;
import org.gradle.api.credentials.Credentials;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import java.io.Serializable;
import java.net.URI;
import java.util.*;

/**
 * @author pfnguyen
 */
public class RepositoryListPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry registry;

    @Inject
    public RepositoryListPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        registry.register(new RepositoryListModelBuilder());
    }

    private static class RepositoryListModelBuilder implements ToolingModelBuilder {
        @Override
        public boolean canBuild(String modelName) {
            return modelName.equals(RepositoryListModel.class.getName());
        }

        @Override
        public Object buildAll(String modelName, Project project) {
            return new RM(project.getRepositories());
        }
    }

    public static class RM implements Serializable, RepositoryListModel {
        private final Collection<MavenRepositoryModel> resolvers = new ArrayList<MavenRepositoryModel>();
        public RM(RepositoryHandler rh) {
            for (ArtifactRepository r : rh) {
                if (r instanceof MavenArtifactRepository) {
                    resolvers.add(new MAR((MavenArtifactRepository)r));
                }
            }
        }
        public Collection<MavenRepositoryModel> getResolvers() {
            return resolvers;
        }
    }
    public static class MAR implements MavenRepositoryModel, Serializable {
        private final URI url;
        private final Set<URI> artifactUrls;
        private final String name;
        public MAR(MavenArtifactRepository mar) {
            url = mar.getUrl();
            artifactUrls = mar.getArtifactUrls();
            name = mar.getName();
        }
        public URI getUrl() {
            return url;
        }


        @Override
        public Set<URI> getArtifactUrls() {
            return artifactUrls;
        }


        @Override
        public String getName() {
            return name;
        }
    }
}

