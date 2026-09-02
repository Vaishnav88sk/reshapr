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
package io.reshapr.ctrl.rest.v1;

import io.reshapr.json.HtmlEncodedStringDeserializer;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Data Transfer Object (DTO) for a configuration template in the Reshapr control plane.
 *
 * @author vaishnav
 */
@RegisterForReflection
public class ConfigurationTemplateDTO {

   protected String id;
   protected String organizationId;

   @NotBlank(message = "Name must not be blank")
   @Size(max = 255, message = "Name must not exceed 255 characters")
   @JsonDeserialize(using = HtmlEncodedStringDeserializer.class)
   protected String name;

   @Size(max = 255, message = "Description must not exceed 255 characters")
   @JsonDeserialize(using = HtmlEncodedStringDeserializer.class)
   protected String description;

   protected OAuth2ConfigurationDTO oauth2Configuration;

   public String getId() {
      return id;
   }

   public void setId(String id) {
      this.id = id;
   }

   public String getOrganizationId() {
      return organizationId;
   }

   public void setOrganizationId(String organizationId) {
      this.organizationId = organizationId;
   }

   public String getName() {
      return name;
   }

   public void setName(String name) {
      this.name = name;
   }

   public String getDescription() {
      return description;
   }

   public void setDescription(String description) {
      this.description = description;
   }

   public OAuth2ConfigurationDTO getOauth2Configuration() {
      return oauth2Configuration;
   }

   public void setOauth2Configuration(OAuth2ConfigurationDTO oauth2Configuration) {
      this.oauth2Configuration = oauth2Configuration;
   }
}
