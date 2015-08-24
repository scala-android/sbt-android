package com.hanhuy.gradle.discovery;

import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import java.util.Collection;

/**
 * @author pfnguyen
 */
public interface RepositoryListModel {
    public Collection<MavenRepositoryModel> getResolvers();
}

