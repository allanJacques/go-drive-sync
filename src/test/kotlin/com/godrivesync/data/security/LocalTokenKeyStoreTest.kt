package com.godrivesync.data.security

import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

/** Exercita arquivos isolados, permissões reais do sistema e estabilidade da chave entre instâncias. */
class LocalTokenKeyStoreTest {
    /** Nunca usa ~/.go-drive-sync; JUnit remove os arquivos ao fim de cada caso. */
    @get:Rule val temporaryFolder = TemporaryFolder()

    /** Caminho cujo diretório privado ainda não existe, igual à primeira execução do aplicativo. */
    private fun keyPath(): Path = temporaryFolder.root.toPath().resolve("private/token-key-v1")

    /** Somente primeira criação autorizada gera chave, recuperável por outra instância do armazenamento. */
    @Test
    fun `cria uma chave e reutiliza ao reabrir`() {
        val path = keyPath()
        assertFailsWith<TokenProtectionException> { LocalTokenKeyStore(path).load() }
        assertFalse(Files.exists(path.parent))
        val key = LocalTokenKeyStore(path).load(createIfMissing = true)
        assertEquals(32, key.encoded.size)
        assertContentEquals(key.encoded, LocalTokenKeyStore(path).load().encoded)
        assertContentEquals(key.encoded, LocalTokenKeyStore(path).load(createIfMissing = true).encoded)
        if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(path.parent)))
        }
    }

    /** Arquivo corrompido não é sobrescrito nem quando a criação estava permitida. */
    @Test
    fun `chave invalida ou perdida nao e regenerada ao ler`() {
        val path = keyPath()
        LocalTokenKeyStore(path).load(true)
        Files.write(path, byteArrayOf(1, 2, 3))
        val failure = assertFailsWith<TokenProtectionException> { LocalTokenKeyStore(path).load(true) }
        assertNull(failure.cause)
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(path))
        Files.delete(path)
        assertFailsWith<TokenProtectionException> { LocalTokenKeyStore(path).load() }
        assertFalse(Files.exists(path))
    }

    /** No POSIX, arquivo/diretório público e links simbólicos são recusados sem alterar seus alvos. */
    @Test
    fun `recusa permissoes amplas e links simbolicos`() {
        val path = keyPath()
        assumeTrue(Files.getFileAttributeView(temporaryFolder.root.toPath(), PosixFileAttributeView::class.java) != null)
        LocalTokenKeyStore(path).load(true)
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-r--r--"))
        assertFailsWith<TokenProtectionException> { LocalTokenKeyStore(path).load() }
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwxr-xr-x"))
        assertFailsWith<TokenProtectionException> { LocalTokenKeyStore(path).load() }
        Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------"))
        val target = path.resolveSibling("original")
        Files.move(path, target)
        Files.createSymbolicLink(path, target)
        assertFailsWith<TokenProtectionException> { LocalTokenKeyStore(path).load(true) }
        assertEquals(32L, Files.size(target))
    }

    /** Instâncias concorrentes no mesmo processo observam a mesma chave, sem escrita parcial. */
    @Test
    fun `criacao concorrente nao substitui chave`() {
        val path = keyPath()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val keys = pool.invokeAll(List(8) { Callable { LocalTokenKeyStore(path).load(true).encoded } })
                .map { it.get(5, TimeUnit.SECONDS) }
            keys.forEach { assertContentEquals(keys.first(), it) }
        } finally {
            pool.shutdownNow()
        }
    }
}
