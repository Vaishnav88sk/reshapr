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

import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.UserRepository;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Resource for user profile operations in the public API.
 * @author laurent
 */
@RunOnVirtualThread
@Path("/api/v1/user")
@Authenticated
public class UserProfileResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final UserRepository userRepository;
   private final Mappers v1Mappers;

   /**
    * Create a new UserProfileResource with the given dependencies.
    * @param userRepository The user repository.
    * @param v1Mappers The mappers for converting between DTOs and entities.
    */
   public UserProfileResource(UserRepository userRepository, Mappers v1Mappers) {
      this.userRepository = userRepository;
      this.v1Mappers = v1Mappers;
   }

   @GET
   @Path("/profile")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getUserOrganizations(@Context SecurityIdentity securityIdentity) {
      String username = securityIdentity.getPrincipal().getName();
      logger.debugf("Getting user profile for user: %s", username);

      User user = userRepository.findByUsername(username);
      if (user == null) {
         return Response.status(Response.Status.NOT_FOUND).entity("User not found").build();
      }

      return Response.ok(getUserProfile(user)).build();
   }

   @PUT
   @Path("/profile/defaultOrganization")
   @Produces(MediaType.APPLICATION_JSON)
   @Transactional
   public Response setDefaultOrganization(@Context SecurityIdentity securityIdentity, String organizationName) {
      String username = securityIdentity.getPrincipal().getName();
      logger.debugf("Setting default organization for user: %s", username);

      User user = userRepository.findByUsername(username);
      if (user == null) {
         return Response.status(Response.Status.NOT_FOUND).entity("User not found").build();
      }

      // Assign the default organization to the user, if within the user's organizations.'
      user.defaultOrganization = user.organizations.stream()
            .filter(org -> org.name.equals(organizationName))
            .findFirst()
            .orElse(null);
      userRepository.persistAndFlush(user);
      return Response.ok(getUserProfile(user)).build();
   }

   private UserProfileDTO getUserProfile(User user) {
      return new UserProfileDTO(user.firstname, user.lastname,
            user.defaultOrganization != null ? user.defaultOrganization.name : null,
            user.organizations.stream().map(o -> new UserOrganizationDTO(
                  o.name, o.description, o.icon, o.owner != null && o.owner.username.equals(user.username)
            )).toList());
   }
}

