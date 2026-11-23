package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InviteLinkTest {
    private val code = "cgi_" + "Ab1-_".repeat(9)

    @Test
    fun `parses link from the join page`() {
        val link = InviteLink.parse("cgconnector://join?server=https%3A%2F%2Fcg.example.ngrok-free.dev&code=$code")

        assertThat(link).isEqualTo(InviteLink("https://cg.example.ngrok-free.dev", code))
    }

    @Test
    fun `rejects other links`() {
        assertThat(InviteLink.parse(null)).isNull()
        assertThat(InviteLink.parse("https://cg.example.ngrok-free.dev/join?code=$code")).isNull()
        assertThat(InviteLink.parse("cgconnector://other?server=https%3A%2F%2Fa.b&code=$code")).isNull()
        assertThat(InviteLink.parse("cgconnector://join?server=http%3A%2F%2Fa.b&code=$code")).isNull()
        assertThat(InviteLink.parse("cgconnector://join?server=https%3A%2F%2Fa.b&code=cga_xyz")).isNull()
        assertThat(InviteLink.parse("cgconnector://join?server=https%3A%2F%2Fa.b")).isNull()
    }
}
