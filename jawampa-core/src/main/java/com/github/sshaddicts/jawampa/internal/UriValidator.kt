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

import com.github.sshaddicts.jawampa.ApplicationError
import java.util.regex.Pattern

object UriValidator {

    private val LOOSE_URI = Pattern.compile("^([^\\s.#]+\\.)*([^\\s.#]+)$")
    private val LOOSE_URI_PREFIX = Pattern.compile("^([^\\s.#]+\\.)*([^\\s.#]+)?$")
    private val LOOSE_URI_WILDCARD = Pattern.compile("^(([^\\s.#]+\\.)|\\.)*([^\\s.#]+)?$")

    private val STRICT_URI = Pattern.compile("^([0-9a-z_]+\\.)*([0-9a-z_]+)$")
    private val STRICT_URI_PREFIX = Pattern.compile("^([0-9a-z_]+\\.)*([0-9a-z_]+)?$")
    private val STRICT_URI_WILDCARD = Pattern.compile("^(([0-9a-z_]+\\.)|\\.)*([0-9a-z_]+)?$")

    private fun tryValidate(uri: String?, pattern: Pattern): Boolean {
        return uri != null && pattern.matcher(uri).matches()
    }

    @Throws(ApplicationError::class)
    private fun validate(uri: String, pattern: Pattern) {
        val isValid = tryValidate(uri, pattern)
        if (!isValid)
            throw ApplicationError(ApplicationError.INVALID_URI)
    }

    /**
     * Checks a WAMP Uri for validity.
     * @param uri The uri that should be validated
     * @param useStrictValidation Whether the strict Uri validation pattern
     * described in the WAMP spec shall be used for validation
     * @return true if Uri is valid, false otherwise
     */
    fun tryValidate(uri: String, useStrictValidation: Boolean): Boolean {
        return tryValidate(uri, if (useStrictValidation) STRICT_URI else LOOSE_URI)
    }

    /**
     * Checks a WAMP Prefix Uri for validity.
     * @param uri The prefix uri that should be validated
     * @param useStrictValidation Whether the strict Uri validation pattern
     * described in the WAMP spec shall be used for validation
     * @return true if Prefix Uri is valid, false otherwise
     */
    fun tryValidatePrefix(uri: String, useStrictValidation: Boolean): Boolean {
        return tryValidate(uri, if (useStrictValidation) STRICT_URI_PREFIX else LOOSE_URI_PREFIX)
    }

    /**
     * Checks a WAMP Wildcard Uri for validity.
     * @param uri The wildcard uri that should be validated
     * @param useStrictValidation Whether the strict Uri validation pattern
     * described in the WAMP spec shall be used for validation
     * @return true if Wildcard Uri is valid, false otherwise
     */
    fun tryValidateWildcard(uri: String, useStrictValidation: Boolean): Boolean {
        return tryValidate(uri, if (useStrictValidation) STRICT_URI_WILDCARD else LOOSE_URI_WILDCARD)
    }

    /**
     * Checks a WAMP Uri for validity.
     * @param uri The uri that should be validated
     * @param useStrictValidation Whether the strict Uri validation pattern
     * described in the WAMP spec shall be used for validation
     * @throws ApplicationError If the Uri is not valid
     */
    @Throws(ApplicationError::class)
    fun validate(uri: String, useStrictValidation: Boolean) {
        validate(uri, if (useStrictValidation) STRICT_URI else LOOSE_URI)
    }

    /**
     * Checks a WAMP Prefix Uri for validity.
     * @param uri The prefix uri that should be validated
     * @param useStrictValidation Whether the strict Uri validation pattern
     * described in the WAMP spec shall be used for validation
     * @throws ApplicationError If the Prefix Uri is not valid
     */
    @Throws(ApplicationError::class)
    fun validatePrefix(uri: String, useStrictValidation: Boolean) {
        validate(uri, if (useStrictValidation) STRICT_URI_PREFIX else LOOSE_URI_PREFIX)
    }

    /**
     * Checks a WAMP Wildcard Uri for validity.
     * @param uri The wildcard uri that should be validated
     * @param useStrictValidation Whether the strict Uri validation pattern
     * described in the WAMP spec shall be used for validation
     * @throws ApplicationError If the Wildcard Uri is not valid
     */
    @Throws(ApplicationError::class)
    fun validateWildcard(uri: String, useStrictValidation: Boolean) {
        validate(uri, if (useStrictValidation) STRICT_URI_WILDCARD else LOOSE_URI_WILDCARD)
    }
}
