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

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;

/**
 * Represents an operation that can be performed on a service.
 * @author laurent
 */
@Embeddable
public class Operation {

   @Column(nullable = false)
   public String name;
   public String method;
   public String action;

   @Column(name = "input_name")
   @Convert(converter = TrimmedStringConverter.class)
   public String inputName;
   @Column(name = "output_name")
   @Convert(converter = TrimmedStringConverter.class)
   public String outputName;
}
