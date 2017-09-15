package com.github.sshaddicts.jawampa.auth.client

import com.fasterxml.jackson.databind.ObjectMapper
import com.github.sshaddicts.jawampa.WampMessages.AuthenticateMessage
import com.github.sshaddicts.jawampa.WampMessages.ChallengeMessage

class Ticket(private val ticket: String) : ClientSideAuthentication {

    override val authMethod: String
        get() = AUTH_METHOD

    override fun handleChallenge(message: ChallengeMessage,
                                 objectMapper: ObjectMapper): AuthenticateMessage {
        return AuthenticateMessage(ticket, objectMapper.createObjectNode())
    }

    companion object {
        val AUTH_METHOD = "ticket"
    }
}
