/*
 * Copyright 2018-present Open Networking Foundation
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
package io.atomix.primitive.protocol.map;

import io.atomix.primitive.PrimitiveManagementService;
import io.atomix.primitive.protocol.GossipProtocol;

/**
 * Navigable map protocol provider.
 */
public interface NavigableMapProtocolProvider extends GossipProtocol {

  /**
   * Returns a new navigable map protocol.
   *
   * @param name the map name
   * @param managementService the primitive management service
   * @return a new map protocol
   */
  <K, V> NavigableMapProtocol<K, V> newNavigableMapProtocol(String name, PrimitiveManagementService managementService);

}