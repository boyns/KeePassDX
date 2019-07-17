/*
 * Copyright 2017 Brian Pellin, Jeremy Jamet / Kunzisoft.
 *     
 * This file is part of KeePass DX.
 *
 *  KeePass DX is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  KeePass DX is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with KeePass DX.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.kunzisoft.keepass.database.element

import android.util.Log
import biz.source_code.base64Coder.Base64Coder
import com.kunzisoft.keepass.crypto.CryptoUtil
import com.kunzisoft.keepass.crypto.engine.AesEngine
import com.kunzisoft.keepass.crypto.engine.CipherEngine
import com.kunzisoft.keepass.crypto.keyDerivation.KdfEngine
import com.kunzisoft.keepass.crypto.keyDerivation.KdfFactory
import com.kunzisoft.keepass.crypto.keyDerivation.KdfParameters
import com.kunzisoft.keepass.database.exception.InvalidKeyFileException
import com.kunzisoft.keepass.database.exception.UnknownKDF
import com.kunzisoft.keepass.database.file.PwCompressionAlgorithm
import com.kunzisoft.keepass.utils.VariantDictionary
import org.w3c.dom.Node
import org.w3c.dom.Text
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.*
import javax.xml.parsers.DocumentBuilderFactory


class PwDatabaseV4 : PwDatabase<PwGroupV4, PwEntryV4>() {

    var hmacKey: ByteArray? = null
        private set
    var dataCipher = AesEngine.CIPHER_UUID
    private var dataEngine: CipherEngine = AesEngine()
    var compressionAlgorithm = PwCompressionAlgorithm.Gzip
    var kdfParameters: KdfParameters? = null
    private var numKeyEncRounds: Long = 0
    var publicCustomData = VariantDictionary()

    var name = "KeePass DX database"
    var nameChanged = PwDate()
    // TODO change setting date
    var settingsChanged = PwDate()
    var description = ""
    var descriptionChanged = PwDate()
    var defaultUserName = ""
    var defaultUserNameChanged = PwDate()

    // TODO date
    var keyLastChanged = PwDate()
    var keyChangeRecDays: Long = -1
    var keyChangeForceDays: Long = 1
    var isKeyChangeForceOnce = false

    var maintenanceHistoryDays: Long = 365
    var color = ""
    /**
     * Determine if RecycleBin is enable or not
     * @return true if RecycleBin enable, false if is not available or not enable
     */
    var isRecycleBinEnabled = true
    var recycleBinUUID: UUID = UUID_ZERO
    // TODO recyclebin Date
    var recycleBinChanged = Date()
    var entryTemplatesGroup = UUID_ZERO
    var entryTemplatesGroupChanged = PwDate()
    var historyMaxItems = DEFAULT_HISTORY_MAX_ITEMS
    var historyMaxSize = DEFAULT_HISTORY_MAX_SIZE
    var lastSelectedGroup = UUID_ZERO
    var lastTopVisibleGroup = UUID_ZERO
    var memoryProtection = MemoryProtectionConfig()
    val deletedObjects = ArrayList<PwDeletedObject>()
    val customIcons = ArrayList<PwIconCustom>()
    val customData = HashMap<String, String>()

    var binPool = BinaryPool()

    var localizedAppName = "KeePassDX" // TODO resource

    override val version: String
        get() = "KeePass 2"

    override val availableEncryptionAlgorithms: List<PwEncryptionAlgorithm>
        get() {
            val list = ArrayList<PwEncryptionAlgorithm>()
            list.add(PwEncryptionAlgorithm.AESRijndael)
            list.add(PwEncryptionAlgorithm.Twofish)
            list.add(PwEncryptionAlgorithm.ChaCha20)
            return list
        }

    val kdfEngine: KdfEngine?
        get() {
            try {
                return KdfFactory.getEngineV4(kdfParameters)
            } catch (unknownKDF: UnknownKDF) {
                Log.i(TAG, "Unable to retrieve KDF engine", unknownKDF)
                return null
            }

        }

    override var numberKeyEncryptionRounds: Long
        get() {
            if (kdfEngine != null && kdfParameters != null)
                numKeyEncRounds = kdfEngine!!.getKeyRounds(kdfParameters)
            return numKeyEncRounds
        }
        @Throws(NumberFormatException::class)
        set(rounds) {
            if (kdfEngine != null && kdfParameters != null)
                kdfEngine!!.setKeyRounds(kdfParameters, rounds)
            numKeyEncRounds = rounds
        }

    var memoryUsage: Long
        get() = if (kdfEngine != null && kdfParameters != null) {
            kdfEngine!!.getMemoryUsage(kdfParameters)
        } else KdfEngine.UNKNOW_VALUE.toLong()
        set(memory) {
            if (kdfEngine != null && kdfParameters != null)
                kdfEngine!!.setMemoryUsage(kdfParameters, memory)
        }

    var parallelism: Int
        get() = if (kdfEngine != null && kdfParameters != null) {
            kdfEngine!!.getParallelism(kdfParameters)
        } else KdfEngine.UNKNOW_VALUE
        set(parallelism) {
            if (kdfEngine != null && kdfParameters != null)
                kdfEngine!!.setParallelism(kdfParameters, parallelism)
        }

    override val passwordEncoding: String
        get() = "UTF-8"

    // TODO delete recycle bin preference
    val recycleBin: PwGroupV4?
        get() {
            if (recycleBinUUID == null) {
                return null
            }

            val recycleId = PwNodeIdUUID(recycleBinUUID!!)
            return groupIndexes[recycleId]
        }

    fun setDataEngine(dataEngine: CipherEngine) {
        this.dataEngine = dataEngine
    }

    fun getCustomIcons(): List<PwIconCustom> {
        return customIcons
    }

    fun addCustomIcon(customIcon: PwIconCustom) {
        this.customIcons.add(customIcon)
    }

    fun getCustomData(): Map<String, String> {
        return customData
    }

    fun putCustomData(label: String, value: String) {
        this.customData[label] = value
    }

    @Throws(InvalidKeyFileException::class, IOException::class)
    public override fun getMasterKey(key: String?, keyInputStream: InputStream?): ByteArray {

        var fKey = byteArrayOf()

        if (key != null && keyInputStream != null) {
            return getCompositeKey(key, keyInputStream)
        } else if (key != null) { // key.length() >= 0
            fKey = getPasswordKey(key)
        } else if (keyInputStream != null) { // key == null
            fKey = getFileKey(keyInputStream)
        }

        val md: MessageDigest
        try {
            md = MessageDigest.getInstance("SHA-256")
        } catch (e: NoSuchAlgorithmException) {
            throw IOException("No SHA-256 implementation")
        }

        return md.digest(fKey)
    }

    @Throws(IOException::class)
    fun makeFinalKey(masterSeed: ByteArray) {

        val kdfEngine = KdfFactory.getEngineV4(kdfParameters)

        var transformedMasterKey = kdfEngine.transform(masterKey, kdfParameters)
        if (transformedMasterKey.size != 32) {
            transformedMasterKey = CryptoUtil.hashSha256(transformedMasterKey)
        }

        val cmpKey = ByteArray(65)
        System.arraycopy(masterSeed, 0, cmpKey, 0, 32)
        System.arraycopy(transformedMasterKey, 0, cmpKey, 32, 32)
        finalKey = CryptoUtil.resizeKey(cmpKey, 0, 64, dataEngine.keyLength())

        val md: MessageDigest
        try {
            md = MessageDigest.getInstance("SHA-512")
            cmpKey[64] = 1
            hmacKey = md.digest(cmpKey)
        } catch (e: NoSuchAlgorithmException) {
            throw IOException("No SHA-512 implementation")
        } finally {
            Arrays.fill(cmpKey, 0.toByte())
        }
    }

    override fun loadXmlKeyFile(keyInputStream: InputStream): ByteArray? {
        try {
            val dbf = DocumentBuilderFactory.newInstance()
            val db = dbf.newDocumentBuilder()
            val doc = db.parse(keyInputStream)

            val el = doc.documentElement
            if (el == null || !el.nodeName.equals(RootElementName, ignoreCase = true)) {
                return null
            }

            val children = el.childNodes
            if (children.length < 2) {
                return null
            }

            for (i in 0 until children.length) {
                val child = children.item(i)

                if (child.nodeName.equals(KeyElementName, ignoreCase = true)) {
                    val keyChildren = child.childNodes
                    for (j in 0 until keyChildren.length) {
                        val keyChild = keyChildren.item(j)
                        if (keyChild.nodeName.equals(KeyDataElementName, ignoreCase = true)) {
                            val children2 = keyChild.childNodes
                            for (k in 0 until children2.length) {
                                val text = children2.item(k)
                                if (text.nodeType == Node.TEXT_NODE) {
                                    val txt = text as Text
                                    return Base64Coder.decode(txt.nodeValue)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }

        return null
    }

    override fun newGroupId(): PwNodeIdUUID {
        var newId: PwNodeIdUUID
        do {
            newId = PwNodeIdUUID()
        } while (isGroupIdUsed(newId))

        return newId
    }

    override fun newEntryId(): PwNodeIdUUID {
        var newId: PwNodeIdUUID
        do {
            newId = PwNodeIdUUID()
        } while (isEntryIdUsed(newId))

        return newId
    }

    override fun createGroup(): PwGroupV4 {
        return PwGroupV4()
    }


    override fun createEntry(): PwEntryV4 {
        return PwEntryV4()
    }

    override fun isBackup(group: PwGroupV4): Boolean {
        return if (!isRecycleBinEnabled) {
            false
        } else group.isContainedIn(recycleBin!!)

    }

    /**
     * Ensure that the recycle bin tree exists, if enabled and create it
     * if it doesn't exist
     */
    private fun ensureRecycleBin() {
        if (recycleBin == null) {
            // Create recycle bin

            val recycleBin = createGroup()
            recycleBin.title = RECYCLEBIN_NAME
            recycleBin.icon = iconFactory.trashIcon
            recycleBin.enableAutoType = false
            recycleBin.enableSearching = false
            recycleBin.isExpanded = false
            addGroupTo(recycleBin, rootGroup)

            recycleBinUUID = recycleBin.id
        }
    }

    /**
     * Define if a Group must be delete or recycle
     * @param group Group to remove
     * @return true if group can be recycle, false elsewhere
     */
    fun canRecycle(group: PwGroupV4): Boolean {
        if (!isRecycleBinEnabled) {
            return false
        }
        val recycle = recycleBin
        return recycle == null || !group.isContainedIn(recycle)
    }

    /**
     * Define if an Entry must be delete or recycle
     * @param entry Entry to remove
     * @return true if entry can be recycle, false elsewhere
     */
    fun canRecycle(entry: PwEntryV4): Boolean {
        if (!isRecycleBinEnabled) {
            return false
        }
        val parent = entry.parent
        return parent != null && canRecycle(parent)
    }

    fun recycle(group: PwGroupV4) {
        ensureRecycleBin()

        removeGroupFrom(group, group.parent)

        addGroupTo(group, recycleBin)

        // TODO ? group.afterChangeParent();
    }

    fun recycle(entry: PwEntryV4) {
        ensureRecycleBin()

        removeEntryFrom(entry, entry.parent)

        addEntryTo(entry, recycleBin)

        entry.afterChangeParent()
    }

    fun undoRecycle(group: PwGroupV4, origParent: PwGroupV4) {

        removeGroupFrom(group, recycleBin)

        addGroupTo(group, origParent)
    }

    fun undoRecycle(entry: PwEntryV4, origParent: PwGroupV4) {

        removeEntryFrom(entry, recycleBin)

        addEntryTo(entry, origParent)
    }

    fun getDeletedObjects(): List<PwDeletedObject> {
        return deletedObjects
    }

    fun addDeletedObject(deletedObject: PwDeletedObject) {
        this.deletedObjects.add(deletedObject)
    }

    override fun removeEntryFrom(entryToRemove: PwEntryV4, parent: PwGroupV4?) {
        super.removeEntryFrom(entryToRemove, parent)
        deletedObjects.add(PwDeletedObject(entryToRemove.id))
    }

    override fun undoDeleteEntryFrom(entry: PwEntryV4, origParent: PwGroupV4?) {
        super.undoDeleteEntryFrom(entry, origParent)
        deletedObjects.remove(PwDeletedObject(entry.id))
    }

    fun containsPublicCustomData(): Boolean {
        return publicCustomData.size() > 0
    }

    override fun isGroupSearchable(group: PwGroupV4?, omitBackup: Boolean): Boolean {
        return if (!super.isGroupSearchable(group, omitBackup)) {
            false
        } else group!!.isSearchingEnabled
    }

    override fun validatePasswordEncoding(key: String?): Boolean {
        return true
    }

    fun clearCache() {
        binPool.clear()
    }

    companion object {
        private val TAG = PwDatabaseV4::class.java.name

        private const val DEFAULT_HISTORY_MAX_ITEMS = 10 // -1 unlimited
        private const val DEFAULT_HISTORY_MAX_SIZE = (6 * 1024 * 1024).toLong() // -1 unlimited
        private const val RECYCLEBIN_NAME = "RecycleBin"

        private const val RootElementName = "KeyFile"
        //private const val MetaElementName = "Meta";
        //private const val VersionElementName = "Version";
        private const val KeyElementName = "Key"
        private const val KeyDataElementName = "Data"
    }
}