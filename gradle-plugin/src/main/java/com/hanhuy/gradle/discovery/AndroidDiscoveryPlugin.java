package com.hanhuy.gradle.discovery;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.tooling.provider.model.ToolingModelBuilder;
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry;

import javax.inject.Inject;
import java.io.Serializable;

/**
 * @author pfnguyen
 */
public class AndroidDiscoveryPlugin implements Plugin<Project> {
    private final ToolingModelBuilderRegistry registry;

    @Inject
    public AndroidDiscoveryPlugin(ToolingModelBuilderRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void apply(Project project) {
        registry.register(new AndroidDiscoveryModelBuilder());
    }

    private static class AndroidDiscoveryModelBuilder implements ToolingModelBuilder {
        @Override
        public boolean canBuild(String modelName) {
            return modelName.equals(AndroidDiscoveryModel.class.getName());
        }

        @Override
        public Object buildAll(String modelName, Project project) {
            return new AndroidDiscovery(
                    project.getPlugins().hasPlugin("com.android.application"),
                    project.getPlugins().hasPlugin("com.android.library"));
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
}

