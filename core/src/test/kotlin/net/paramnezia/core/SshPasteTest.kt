package net.paramnezia.core

import net.paramnezia.core.profile.SshPaste
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

class SshPasteTest {
    @Test fun hosterMailInRussian() {
        val f = SshPaste.parse(
            """
            Ваш сервер готов!
            IP-адрес: 167.88.172.234
            Логин: root
            Пароль: Xy7:pq#9 z
            Порт SSH: 48424
            Панель управления: https://panel.example.com
            Почта для связи: help@example.com
            """.trimIndent()
        )
        assertEquals("167.88.172.234", f.host)
        assertEquals("root", f.user)
        assertEquals("Xy7:pq#9 z", f.password)
        assertEquals(48424, f.port)
    }

    @Test fun hosterMailInEnglish() {
        val f = SshPaste.parse("Server details\nIP Address: 203.0.113.7\nUsername: admin\nRoot password: s3cr3t!\n")
        assertEquals("203.0.113.7", f.host)
        assertEquals("admin", f.user)
        assertEquals("s3cr3t!", f.password)
        assertNull(f.port)
    }

    @Test fun sshCommand() {
        val f = SshPaste.parse("ssh -p 2222 ubuntu@vpn.example.org")
        assertEquals("vpn.example.org", f.host); assertEquals("ubuntu", f.user); assertEquals(2222, f.port)
        val g = SshPaste.parse("ssh root@1.2.3.4 -p2200 -i ~/.ssh/id")
        assertEquals("1.2.3.4", g.host); assertEquals("root", g.user); assertEquals(2200, g.port)
    }

    @Test fun bareForms() {
        assertEquals(SshPaste.Found(host = "1.2.3.4"), SshPaste.parse(" 1.2.3.4 \n"))
        assertEquals(SshPaste.Found(host = "1.2.3.4", port = 22, user = "root"), SshPaste.parse("root@1.2.3.4:22"))
        assertEquals(SshPaste.Found(host = "my.host.ru", port = 2022), SshPaste.parse("my.host.ru:2022"))
        assertEquals(SshPaste.Found(host = "2001:db8::1", port = 22), SshPaste.parse("[2001:db8::1]:22"))
        assertTrue(SshPaste.parse("hello").isEmpty)
        assertTrue(SshPaste.parse("").isEmpty)
    }

    @Test fun ipSomewhereInText() {
        assertEquals("10.20.30.40", SshPaste.parse("ваш vps 10.20.30.40 активирован").host)
    }

    @Test fun privateKey() {
        val key = "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAAAAA\n-----END OPENSSH PRIVATE KEY-----"
        val f = SshPaste.parse(key)
        assertEquals(key, f.privateKey); assertNull(f.host); assertNull(f.password)
        assertTrue(SshPaste.isPrivateKey(key))
        assertEquals("SSH-ключ OpenSSH", SshPaste.describeKey(key))
        val g = SshPaste.parse("IP: 1.2.3.4\n$key")
        assertEquals("1.2.3.4", g.host); assertEquals(key, g.privateKey)
    }
}
