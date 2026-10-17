package app.aaps.core.nssdk.networking

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class NetworkStackBuilderTest {

    @Test fun `the access token in the refresh request path is masked`() {
        val line = "--> GET https://ns.example.com/api/v2/authorization/request/my-secret-access-token h2"
        assertThat(NetworkStackBuilder.redactSecrets(line))
            .isEqualTo("--> GET https://ns.example.com/api/v2/authorization/request/██ h2")
    }

    @Test fun `the jwt in the refresh response body is masked`() {
        val line = """{"status":200,"token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig","sub":"x","exp":1}"""
        assertThat(NetworkStackBuilder.redactSecrets(line))
            .isEqualTo("""{"status":200,"token":"${"██"}","sub":"x","exp":1}""")
    }

    @Test fun `other lines are unchanged`() {
        val lines = listOf(
            "--> GET https://ns.example.com/api/v3/entries?limit=10 h2",
            """{"status":200,"result":[{"sgv":123,"device":"Libre3"}]}""",
            "<-- 200 https://ns.example.com/api/v3/treatments (123ms)",
            ""
        )
        for (line in lines) assertThat(NetworkStackBuilder.redactSecrets(line)).isEqualTo(line)
    }
}
