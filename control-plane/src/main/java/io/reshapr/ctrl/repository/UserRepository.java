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
package io.reshapr.ctrl.repository;

import io.quarkus.elytron.security.common.BcryptUtil;
import io.reshapr.ctrl.model.User;
import io.quarkus.hibernate.orm.panache.PanacheRepository;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * UserRepository is a repository interface for managing User entities.
 * @author laurent
 */
@ApplicationScoped
public class UserRepository implements PanacheRepository<User> {

   /**
    * Finds a user by their username.
    * @param username the username of the user to find
    * @return the User entity if found, otherwise null
    */
   public User findByUsername(String username) {
      return find("username", username).firstResult();
   }

   /**
    * Finds a user by their email.
    * @param email the email of the user to find
    * @return the User entity if found, otherwise null
    */
   public User findByEmail(String email) {
      return find("email", email).firstResult();
   }

   /**
    * Adds a new user to the repository.
    * If the user has a password, it hashes the password before persisting.
    * @param user the User entity to add
    */
   public void addUser(User user) {
      if (user.password != null) {
         user.password = BcryptUtil.bcryptHash(user.password);
      }
      persist(user);
   }
}
