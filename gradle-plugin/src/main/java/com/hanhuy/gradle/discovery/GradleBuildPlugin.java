package com.hanhuy.gradle.discovery;

import com.android.builder.model.AndroidProject;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.dsl.RepositoryHandler;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;
import java.io.Serializable;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * @author pfnguyen
 */
public class GradleBuildPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry registry;

    @Inject
    public GradleBuildPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        registry.register(new GradleBuildModelBuilder(registry));
    }

    private static class GradleBuildModelBuilder implements ToolingModelBuilder {
        private final ToolingModelBuilderRegistry registry;
        public GradleBuildModelBuilder(ToolingModelBuilderRegistry registry) {
            this.registry = registry;
        }
        @Override
        public boolean canBuild(String modelName) {
            return modelName.equals(GradleBuildModel.class.getName());
        }

        @Override
        public Object buildAll(String modelName, Project project) {
            return new GradleBuildM(project, registry);
        }
    }

    public static class GradleBuildM implements Serializable {
        private final static String GRADLE_PROJECT = GradleProject.class.getName();
        private final static String ANDROID_PROJECT = AndroidProject.class.getName();
        private final AndroidDiscoveryModel discovery;
        private final RepositoryListModel repositories;
        private final Object gradleProject;
        private final Object androidProject;
        public GradleBuildM(Project project, ToolingModelBuilderRegistry registry) {
            ToolingModelBuilder b;
            discovery = new AndroidDiscovery(
                    project.getPlugins().hasPlugin("com.android.application"),
                    project.getPlugins().hasPlugin("com.android.library"));
            repositories = new RM(project.getRepositories());
            b = registry.getBuilder(GRADLE_PROJECT);
            gradleProject = b.buildAll(GRADLE_PROJECT, project);
            if (discovery.isApplication() || discovery.isLibrary()) {
                b = registry.getBuilder(ANDROID_PROJECT);
                androidProject = b.buildAll(ANDROID_PROJECT, project);
            } else {
                androidProject = null;
            }
        }
        public RepositoryListModel getRepositories() {
            return repositories;
        }

        public AndroidDiscoveryModel getDiscovery() {
            return discovery;
        }

        public Object getAndroidProject() {
            return androidProject;
        }

        public Object getGradleProject() {
            return gradleProject;
        }
    }
    public static class AndroidDiscovery implements Serializable, AndroidDiscoveryModel {
        public final boolean hasApplicationPlugin;

        public boolean isLibrary() {
            return hasLibraryPlugin;
        }

        public final boolean hasLibraryPlugin;

        public AndroidDiscovery(boolean hasApplicationPlugin, boolean hasLibraryPlugin) {
            this.hasApplicationPlugin = hasApplicationPlugin;
            this.hasLibraryPlugin = hasLibraryPlugin;
        }

        public boolean isApplication() {
            return hasApplicationPlugin;
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

