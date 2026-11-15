package com.godrivesync.data.security

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.attribute.UserPrincipal
import java.security.SecureRandom
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Guarda a chave AES fora do banco/repositório, por padrão em ~/.go-drive-sync/token-key-v1.
 * É proteção por permissões do SO, não um cofre: quem lê a chave e o H2 pode recuperar tokens.
 * Chamadas são bloqueantes e devem ocorrer em Dispatchers.IO, como acesso a arquivo em Java/Swing.
 * [path] permite diretórios temporários nos testes e instalações com localização própria.
 */
class LocalTokenKeyStore(private val path: Path = Path.of(System.getProperty("user.home"), ".go-drive-sync", "token-key-v1")) {
    /** Serializa criação/leitura entre instâncias no mesmo processo; CREATE_NEW impede sobrescrita externa. */
    private companion object {
        val lock = Any()
    }

    /**
     * Lê exatamente 32 bytes. Só permite criar quando [createIfMissing] foi autorizado para banco vazio.
     * Chave vazia, inválida, permissões amplas ou links simbólicos causam erro, nunca regeneração silenciosa.
     * Uma interrupção durante a primeira escrita pode deixar arquivo inválido, que será recusado ao reabrir.
     */
    fun load(createIfMissing: Boolean = false): SecretKey = synchronized(lock) {
        try {
            val file = path.toAbsolutePath().normalize()
            val directory = file.parent
            val owner = file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
            if (!Files.exists(directory, NOFOLLOW_LINKS)) {
                check(createIfMissing)
                Files.createDirectory(directory, privateAttribute(directory.parent, owner, directory = true))
            }
            verifyPrivate(directory, owner, directory = true)
            if (!Files.exists(file, NOFOLLOW_LINKS)) {
                check(createIfMissing)
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                try {
                    // A permissão nasce restrita; não há intervalo com chave legível por outros usuários.
                    Files.newByteChannel(file, setOf(CREATE_NEW, WRITE, NOFOLLOW_LINKS),
                        privateAttribute(directory, owner, directory = false)).use { channel ->
                        val buffer = ByteBuffer.wrap(bytes)
                        while (buffer.hasRemaining()) channel.write(buffer)
                    }
                } finally {
                    bytes.fill(0)
                }
            }
            verifyPrivate(file, owner, directory = false)
            check(Files.size(file) == 32L)
            val bytes = Files.readAllBytes(file)
            try {
                check(bytes.size == 32)
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } catch (_: Exception) {
            throw TokenProtectionException("Chave local ausente, inválida ou sem proteção de acesso. Restaure a chave original e confira as permissões.")
        }
    }

    /** Seleciona POSIX (700/600) ou ACL exclusiva do proprietário; sistemas sem ambos falham fechados. */
    private fun privateAttribute(parent: Path, owner: UserPrincipal, directory: Boolean): FileAttribute<*> {
        if (Files.getFileAttributeView(parent, PosixFileAttributeView::class.java, NOFOLLOW_LINKS) != null) {
            return PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        }
        check(Files.getFileAttributeView(parent, AclFileAttributeView::class.java, NOFOLLOW_LINKS) != null)
        val acl = listOf(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(*AclEntryPermission.entries.toTypedArray()).build())
        return object : FileAttribute<List<AclEntry>> {
            /** A ACL inicial evita criar arquivo temporariamente acessível por permissões herdadas. */
            override fun name(): String = "acl:acl"
            /** O proprietário tem controle total; nenhum outro principal recebe uma regra ALLOW. */
            override fun value(): List<AclEntry> = acl
        }
    }

    /** Verifica tipo, proprietário e permissões a cada leitura, sem corrigir arquivos existentes silenciosamente. */
    private fun verifyPrivate(path: Path, owner: UserPrincipal, directory: Boolean) {
        check(if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS))
        check(Files.getOwner(path, NOFOLLOW_LINKS) == owner)
        val posix = Files.getFileAttributeView(path, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            check(posix.readAttributes().permissions() == PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"))
        } else {
            val acl = checkNotNull(Files.getFileAttributeView(path, AclFileAttributeView::class.java, NOFOLLOW_LINKS)).acl
            check(acl.any { it.type() == AclEntryType.ALLOW && it.principal() == owner })
            check(acl.none { it.type() == AclEntryType.ALLOW && it.principal() != owner })
        }
    }
}
