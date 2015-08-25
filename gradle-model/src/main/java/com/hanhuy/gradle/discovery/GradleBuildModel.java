package com.hanhuy.gradle.discovery;

import com.android.builder.model.AndroidProject;
import org.gradle.tooling.model.GradleProject;

/**
 * @author pfnguyen
 */
public interface GradleBuildModel {
    RepositoryListModel getRepositories();
    AndroidDiscoveryModel getDiscovery();
    AndroidProject getAndroidProject();
    GradleProject getGradleProject();
}
