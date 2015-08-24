package com.hanhuy.gradle.discovery;

import java.net.URI;
import java.util.Set;

/**
 * @author pfnguyen
 */
public interface MavenRepositoryModel {
    public URI getUrl();
    public Set<URI> getArtifactUrls();
    public String getName();
}
