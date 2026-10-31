package com.mygallery.app.pro

import android.content.Context
import android.net.Uri
import com.mygallery.app.Vault
import java.io.File

object PrivacyVault {
    fun encryptedFiles(context:Context)=Vault.files(context)
    fun importEncrypted(context:Context,uri:Uri)=Vault.import(context,uri)
    fun decryptForAuthorizedSession(context:Context,file:File)=Vault.decrypt(context,file)
    /** Never back up vault files without a portable password-derived encryption key. */
}
