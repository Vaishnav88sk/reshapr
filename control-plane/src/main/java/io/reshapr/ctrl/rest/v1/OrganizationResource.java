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

import io.reshapr.ctrl.model.Organization;
import io.reshapr.ctrl.model.User;
import io.reshapr.ctrl.repository.OrganizationRepository;
import io.reshapr.ctrl.repository.UserRepository;

import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.RunOnVirtualThread;
import jakarta.transaction.Transactional;
import jakarta.validation.Valid;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;

/**
 * Resource for organization operations in the public API (for end users).
 */
@RunOnVirtualThread
@Path("/api/v1/organizations")
@Authenticated
public class OrganizationResource {

   /** Get a JBoss logging logger. */
   private final Logger logger = Logger.getLogger(getClass());

   private final UserRepository userRepository;
   private final OrganizationRepository organizationRepository;

   public OrganizationResource(UserRepository userRepository, OrganizationRepository organizationRepository) {
      this.userRepository = userRepository;
      this.organizationRepository = organizationRepository;
   }

   @GET
   @Path("/{organizationName}/members")
   @Produces(MediaType.APPLICATION_JSON)
   public Response getMembers(@Context SecurityIdentity securityIdentity, @PathParam("organizationName") String organizationName) {
      String username = securityIdentity.getPrincipal().getName();
      
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         return Response.status(Response.Status.NOT_FOUND).entity("Organization not found").build();
      }
      
      // Only the owner is allowed to manage/view members.
      if (organization.owner == null || !organization.owner.username.equals(username)) {
         return Response.status(Response.Status.FORBIDDEN).entity("Only the owner can view members").build();
      }

      List<MemberDTO> members = organization.members.stream()
            .map(u -> new MemberDTO(u.username, u.email, u.firstname, u.lastname))
            .toList();

      return Response.ok(members).build();
   }

   @POST
   @Path("/{organizationName}/members")
   @Produces(MediaType.APPLICATION_JSON)
   @Transactional
   public Response addMember(@Context SecurityIdentity securityIdentity, @PathParam("organizationName") String organizationName, @Valid MemberRequestDTO memberRequest) {
      String username = securityIdentity.getPrincipal().getName();
      
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         return Response.status(Response.Status.NOT_FOUND).entity("Organization not found").build();
      }

      if (organization.owner == null || !organization.owner.username.equals(username)) {
         return Response.status(Response.Status.FORBIDDEN).entity("Only the owner can add members").build();
      }

      User targetUser = userRepository.findByEmail(memberRequest.email());
      if (targetUser == null) {
         return Response.status(Response.Status.BAD_REQUEST).entity("This email address does not match any existing user account").build();
      }

      if (!targetUser.organizations.contains(organization)) {
         targetUser.organizations.add(organization);
         if (targetUser.defaultOrganization == null) {
            targetUser.defaultOrganization = organization;
         }
         userRepository.persistAndFlush(targetUser);
      }
      
      // We don't want to expose target user's details completely, returning just success status
      return Response.ok().entity("User added as a member").build();
   }

   @DELETE
   @Path("/{organizationName}/members/{email}")
   @Produces(MediaType.APPLICATION_JSON)
   @Transactional
   public Response removeMember(@Context SecurityIdentity securityIdentity, @PathParam("organizationName") String organizationName, @PathParam("email") String email) {
      String username = securityIdentity.getPrincipal().getName();
      
      Organization organization = organizationRepository.findByName(organizationName);
      if (organization == null) {
         return Response.status(Response.Status.NOT_FOUND).entity("Organization not found").build();
      }

      if (organization.owner == null || !organization.owner.username.equals(username)) {
         return Response.status(Response.Status.FORBIDDEN).entity("Only the owner can remove members").build();
      }

      // Check if trying to remove the owner
      User targetUser = userRepository.findByEmail(email);
      if (targetUser == null) {
         // Doesn't exist, safely ignore or return 404
         return Response.status(Response.Status.NOT_FOUND).entity("User not found").build();
      }

      if (targetUser.username.equals(organization.owner.username)) {
         return Response.status(Response.Status.BAD_REQUEST).entity("Cannot remove the owner of the organization").build();
      }

      if (targetUser.organizations.contains(organization)) {
         targetUser.organizations.remove(organization);
         if (targetUser.defaultOrganization != null && targetUser.defaultOrganization.name.equals(organization.name)) {
            targetUser.defaultOrganization = targetUser.organizations.isEmpty() ? null : targetUser.organizations.get(0);
         }
         userRepository.persistAndFlush(targetUser);
      }

      return Response.noContent().build();
   }
}
