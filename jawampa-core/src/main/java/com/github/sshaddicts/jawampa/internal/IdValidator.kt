/*
 * Copyright 2014 Matthias Einwag
 *
 * The jawampa authors license this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.github.sshaddicts.jawampa.internal

/**
 * Validates whether WAMP IDs that are e.g. used in requests
 * are valid.
 */
object IdValidator {

    val MIN_VALID_ID = 0L
    val MAX_VALID_ID = 9007199254740992L // 2^53

    /**
     * Returns true if an ID is a valid WAMP ID and
     * false if not.
     * @param id The ID to validate
     */
    fun isValidId(id: Long): Boolean {
        return id >= MIN_VALID_ID && id <= MAX_VALID_ID
    }
}
