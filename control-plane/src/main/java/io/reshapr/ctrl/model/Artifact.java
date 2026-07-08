/*
 * Copyright The Reshapr Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.reshapr.ctrl.model;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.Type;

import java.util.List;

/**
 * An artifact that defines Servcie in the Reshapr control plane.
 * @author laurent
 */
@Entity
@Table(name = "artifacts", uniqueConstraints = {
      @UniqueConstraint(columnNames = {"service_id", "name"})
})
public class Artifact extends TenantAwareEntity {

   @Column(nullable = false)
   public String name;
   @Column(columnDefinition = "TEXT", nullable = false)
   public String content;

   public String path;

   @Column(name = "source_artifact", nullable = false)
   public String sourceArtifact;
   @Column(name = "main_artifact", nullable = false)
   public boolean mainArtifact = false;

   @Enumerated(EnumType.STRING)
   public ArtifactType type;

   /**
    * Names of the capabilities declared by a custom artifact (Prompts, Resources, CustomTools,
    * ToolsOutputFilters): prompt names, resource/resourceTemplate uris, custom tool names or
    * filtered tool names. Extracted from the artifact content at attachment time (see
    * {@code ReshaprArtifactBuilder}). Empty for non-custom artifacts (OpenAPI, GraphQL, Protobuf…)
    */
   @Type(JsonType.class)
   @Column(columnDefinition = "JSONB")
   public List<String> capabilities;

   @ManyToOne(fetch = FetchType.LAZY)
   public Service service;
}
