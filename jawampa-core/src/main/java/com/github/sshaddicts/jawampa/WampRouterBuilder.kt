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

package com.github.sshaddicts.jawampa

import com.github.sshaddicts.jawampa.internal.RealmConfig
import com.github.sshaddicts.jawampa.internal.UriValidator
import java.util.*

/**
 * The [WampRouterBuilder] must be used to build [WampRouter]
 * instances.<br></br>
 * It allows to configure the instantiated router in detail before creating
 * it through the [.build] method.
 */
class WampRouterBuilder {

    internal var realms: MutableMap<String, RealmConfig> = HashMap()

    /**
     * Builds and returns a new WAMP router from the given specification
     * @return The created WampRouter
     * @throws ApplicationError if any parameter is not invalid
     */
    @Throws(ApplicationError::class)
    fun build(): WampRouter {
        if (realms.size == 0)
            throw ApplicationError(ApplicationError.INVALID_REALM)

        return WampRouter(realms)
    }

    /**
     * Adds a realm that is available through the router
     * @param realmName The name of the realm. Must be a valid WAMP Uri.
     * @param roles The roles that are exposed through the router.<br></br>
     * Must be Broker, Dealer or Both.
     * @param useStrictUriValidation True if strict Uri validation rules shall
     * be used within this realm, false if loose validation rules shall be applied
     * @return This WampRouterBuilder object
     */
    @Throws(ApplicationError::class)
    @JvmOverloads
    fun addRealm(realmName: String?, roles: Array<WampRoles>? = arrayOf(WampRoles.Broker, WampRoles.Dealer), useStrictUriValidation: Boolean = false): WampRouterBuilder {
        if (realmName == null || roles == null)
            throw ApplicationError(ApplicationError.INVALID_REALM)

        // Validate the realm name
        if (!UriValidator.tryValidate(realmName, useStrictUriValidation) || this.realms.containsKey(realmName))
            throw ApplicationError(ApplicationError.INVALID_REALM)

        // Validate and copy the roles
        val roleSet = HashSet<WampRoles>()
        for (r in roles) {
            if (r == null)
                throw ApplicationError(ApplicationError.INVALID_REALM)
            roleSet.add(r)
        }

        // Check for at least one role
        if (roleSet.size == 0)
            throw ApplicationError(ApplicationError.INVALID_REALM)

        val realmConfig = RealmConfig(roleSet, useStrictUriValidation)

        // Insert the new realm configuration
        this.realms.put(realmName, realmConfig)

        return this
    }
}
/**
 * Adds a realm that is available through the router.<br></br>
 * The realm will provide the Broker and Dealer roles and will use
 * loose Uri validation rules.
 * @param realmName The name of the realm. Must be a valid WAMP Uri.
 * @return This WampRouterBuilder object
 */
