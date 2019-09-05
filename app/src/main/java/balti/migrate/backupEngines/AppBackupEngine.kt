package balti.migrate.backupEngines

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.AsyncTask
import android.widget.Toast
import balti.migrate.R
import balti.migrate.backupEngines.utils.BackupUtils
import balti.migrate.extraBackupsActivity.apps.AppBatch
import balti.migrate.utilities.CommonToolKotlin
import balti.migrate.utilities.CommonToolKotlin.Companion.ACTION_BACKUP_PROGRESS
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_APP_LOG
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_APP_NAME
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_BACKUP_NAME
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PART_NUMBER
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PROGRESS_PERCENTAGE
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PROGRESS_TYPE
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PROGRESS_TYPE_APP_PROGRESS
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_PROGRESS_TYPE_MAKING_APP_SCRIPTS
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_SCRIPT_APP_NAME
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_TITLE
import balti.migrate.utilities.CommonToolKotlin.Companion.EXTRA_TOTAL_PARTS
import balti.migrate.utilities.CommonToolKotlin.Companion.FILE_PREFIX_BACKUP_SCRIPT
import balti.migrate.utilities.CommonToolKotlin.Companion.FILE_PREFIX_RETRY_SCRIPT
import balti.migrate.utilities.CommonToolKotlin.Companion.MIGRATE_STATUS
import balti.migrate.utilities.CommonToolKotlin.Companion.PREF_NEW_ICON_METHOD
import java.io.*
import javax.inject.Inject

abstract class AppBackupEngine(private val jobcode: Int, private val bd: BackupIntentData,
                               private val appBatch: AppBatch,
                               private val doBackupInstallers : Boolean,
                               private val busyboxBinaryPath: String) : AsyncTask<Any, Any, Any>() {

    @Inject lateinit var engineContext: Context
    @Inject lateinit var sharedPrefs : SharedPreferences

    companion object {
        var ICON_STRING = ""
    }

    private var BACKUP_PID = -999
    private var isBackupCancelled = false

    private val onBackupComplete by lazy { engineContext as OnBackupComplete }

    private val backupDependencyComponent: BackupDependencyComponent
            by lazy { DaggerBackupDependencyComponent.create() }

    private val commonTools by lazy { CommonToolKotlin(engineContext) }
    private val backupUtils by lazy { BackupUtils() }

    private val actualBroadcast by lazy {
        Intent(ACTION_BACKUP_PROGRESS).apply {
            putExtra(EXTRA_BACKUP_NAME, bd.backupName)
            putExtra(EXTRA_PROGRESS_TYPE, "")
            putExtra(EXTRA_TOTAL_PARTS, bd.totalParts)
            putExtra(EXTRA_PART_NUMBER, bd.partNumber)
            putExtra(EXTRA_PROGRESS_PERCENTAGE, 0)
        }
    }
    private val pm by lazy { engineContext.packageManager }
    private val errorTag by lazy { "[${bd.partNumber}/${bd.totalParts}]" }
    private val backupErrors by lazy { ArrayList<String>(0) }
    private val madePartName by lazy { commonTools.getMadePartName(bd) }
    private val actualDestination by lazy { "${bd.destination}/${bd.backupName}" }

    private var suProcess : Process? = null

    private fun iterateBufferedReader(reader: BufferedReader, loopFunction: (line: String) -> Boolean,
                                      onCancelledFunction: (() -> Unit)? = null, isMasterCancelApplicable: Boolean = true){
        var doBreak = false
        while (true){
            val line : String? = reader.readLine()
            if (line == null) break
            else {
                if (!isMasterCancelApplicable || !isBackupCancelled) {
                    doBreak = loopFunction(line.trim())
                    if (doBreak) break
                }
                else break
            }
        }
        if (isBackupCancelled || doBreak) onCancelledFunction?.invoke()
    }

    private fun systemAppInstallScript(sysAppPackageName: String, sysAppPastingDir: String, appDir: String) {

        val scriptName = "$sysAppPackageName.sh"
        val scriptLocation = "$actualDestination/$scriptName"
        val script = File(scriptLocation)

        val scriptText = "#!sbin/sh\n\n" +
                "mkdir -p " + sysAppPastingDir + "\n" +
                "mv /tmp/" + appDir + "/*.apk " + sysAppPastingDir + "/" + "\n" +
                "cd /tmp/" + "\n" +
                "rm -rf " + appDir + "\n" +
                "rm -rf " + scriptName + "\n"


        File(actualDestination).mkdirs()

        commonTools.tryIt {
            val writer = BufferedWriter(FileWriter(script))
            writer.write(scriptText)
            writer.close()
        }

        script.setExecutable(true, false)
    }

    private fun makeBackupScript(): String?{

        try {
            fun formatName(name: String): String {
                return name.replace(' ', '_')
                        .replace('`', '\'')
                        .replace('"', '\'')
            }

            val title = if (bd.totalParts > 1)
                engineContext.getString(R.string.making_app_script) + " : " + madePartName
            else engineContext.getString(R.string.making_app_script)

            val scriptFile = File(engineContext.filesDir, "$FILE_PREFIX_BACKUP_SCRIPT${bd.partNumber}.sh")
            val scriptWriter = BufferedWriter(FileWriter(scriptFile))
            val appAndDataBackupScript = commonTools.unpackAssetToInternal("backup_app_and_data.sh", "backup_app_and_data.sh", false)

            scriptWriter.write("#!sbin/sh\n\n")
            scriptWriter.write("echo \" \"\n")
            scriptWriter.write("sleep 1\n")
            scriptWriter.write("echo \"--- PID: $$\"\n")
            scriptWriter.write("cp $scriptFile.absolutePath ${engineContext.externalCacheDir}/\n")
            scriptWriter.write("cp $busyboxBinaryPath $actualDestination/\n")

            actualBroadcast.putExtra(EXTRA_PROGRESS_TYPE, EXTRA_PROGRESS_TYPE_MAKING_APP_SCRIPTS)
            actualBroadcast.putExtra(EXTRA_TITLE, title)

            appBatch.appPackets.let {packets ->
                for (i in 0 until packets.size) {

                    if (isBackupCancelled) break

                    val packet = packets[i]

                    val appName = formatName(pm.getApplicationLabel(packet.PACKAGE_INFO.applicationInfo).toString())

                    actualBroadcast.apply {
                        putExtra(EXTRA_PROGRESS_PERCENTAGE, commonTools.getPercentageText(i + 1, packets.size))
                        putExtra(EXTRA_SCRIPT_APP_NAME, appName)
                    }
                    commonTools.LBM?.sendBroadcast(actualBroadcast)

                    val packageName = packet.PACKAGE_INFO.packageName

                    var apkPath = "NULL"
                    var apkName = "NULL"       //has .apk extension
                    if (packet.APP) {
                        apkPath = packet.PACKAGE_INFO.applicationInfo.sourceDir
                        apkName = apkPath.substring(apkPath.lastIndexOf('/') + 1)
                        apkPath = apkPath.substring(0, apkPath.lastIndexOf('/'))
                        apkName = commonTools.applyNamingCorrectionForShell(apkName)
                    }

                    var dataPath = "NULL"
                    var dataName = "NULL"
                    if (packet.DATA) {
                        dataPath = packet.PACKAGE_INFO.applicationInfo.dataDir
                        dataName = dataPath.substring(dataPath.lastIndexOf('/') + 1)
                        dataPath = dataPath.substring(0, dataPath.lastIndexOf('/'))
                    }

                    if (packet.PERMISSION)
                        backupUtils.makePermissionFile(packageName, actualDestination, pm)

                    var versionName: String? = packet.PACKAGE_INFO.versionName
                    versionName = if (versionName == null || versionName == "") "_"
                    else formatName(versionName)

                    val appIcon: String = backupUtils.getIconString(packet.PACKAGE_INFO, pm)
                    var appIconFileName: String? = null
                    if (!sharedPrefs.getBoolean(PREF_NEW_ICON_METHOD, true))
                        appIconFileName = backupUtils.makeIconFile(packageName, appIcon, actualDestination)

                    val echoCopyCommand = "echo \"$MIGRATE_STATUS: $appName (${(i + 1)}/${packets.size}) icon: ${if (appIconFileName == null) appIcon else "$(cat $packageName.icon)"}\"\n"
                    val scriptCommand = "sh $appAndDataBackupScript " +
                            "$packageName $actualDestination " +
                            "$apkPath $apkName " +
                            "$dataPath $dataName " +
                            "$busyboxBinaryPath\n"

                    scriptWriter.write(echoCopyCommand, 0, echoCopyCommand.length)
                    scriptWriter.write(scriptCommand, 0, scriptCommand.length)

                    val isSystem = apkPath.startsWith("/system")
                    if (isSystem) systemAppInstallScript(packageName, apkPath, packageName)

                    backupUtils.makeMetadataFile(
                            isSystem, appName, apkName, "$dataName.tar.gz", appIconFileName,
                            versionName, packet.PERMISSION, packet, bd, doBackupInstallers, actualDestination,
                            if (appIconFileName != null) appIcon else null
                    )
                }
            }

            scriptWriter.write("echo \"--- App files copied ---\"\n")
            scriptWriter.close()

            scriptFile.setExecutable(true)

            return scriptFile.absolutePath
        }
        catch (e: Exception){
            e.printStackTrace()
            backupErrors.add("SCRIPT_MAKING_ERROR$errorTag: ${e.message}")
            return null
        }
    }

    private fun runBackupScript(scriptFileLocation: String){

        try {

            if (!File(scriptFileLocation).exists())
                throw Exception(engineContext.getString(R.string.script_file_does_not_exist))

            suProcess = Runtime.getRuntime().exec("su")
            suProcess?.let {
                val suInputStream = BufferedWriter(OutputStreamWriter(it.outputStream))
                val outputStream = BufferedReader(InputStreamReader(it.inputStream))
                val errorStream = BufferedReader(InputStreamReader(it.errorStream))

                suInputStream.write("sh $scriptFileLocation\n")
                suInputStream.write("exit\n")
                suInputStream.flush()

                var c = 0

                iterateBufferedReader(outputStream, { output ->

                    actualBroadcast.putExtra(EXTRA_PROGRESS_TYPE, EXTRA_PROGRESS_TYPE_APP_PROGRESS)

                    if (output.startsWith("--- PID:")) {
                        commonTools.tryIt {
                            BACKUP_PID = output.substring(output.lastIndexOf(" ") + 1).toInt()
                        }
                    }

                    var line = ""

                    if (output.startsWith(MIGRATE_STATUS)) {

                        line = output.substring(MIGRATE_STATUS.length + 2)

                        if (line.contains("icon:")) {
                            ICON_STRING = line.substring(line.lastIndexOf(' ')).trim()
                            line = line.substring(0, line.indexOf("icon:"))
                        }

                        val title = if (bd.totalParts > 1)
                            engineContext.getString(R.string.backingUp) + " : " + madePartName
                        else engineContext.getString(R.string.backingUp)

                        actualBroadcast.putExtra(EXTRA_APP_NAME, line)
                        actualBroadcast.putExtra(EXTRA_PROGRESS_PERCENTAGE, commonTools.getPercentage(++c, appBatch.appPackets.size))
                        actualBroadcast.putExtra(EXTRA_TITLE, title)

                        commonTools.LBM?.sendBroadcast(actualBroadcast)
                    }

                    actualBroadcast.putExtra(EXTRA_APP_LOG, line)
                    commonTools.LBM?.sendBroadcast(actualBroadcast)

                    return@iterateBufferedReader line == "--- App files copied ---"
                })

                commonTools.tryIt { it.waitFor() }

                iterateBufferedReader(errorStream, { errorLine ->

                    var ignorable = false

                    (BackupUtils.ignorableWarnings + BackupUtils.correctableErrors).forEach {warnings ->
                        if (errorLine.endsWith(warnings)) ignorable = true
                    }

                    if (ignorable) backupErrors.add("APP_BACKUP_ERR$errorTag: $errorLine")
                    else backupErrors.add("APP_BACKUP_SUPPRESSED$errorTag: $errorLine")

                    return@iterateBufferedReader false
                }, null, false)

            }
        }
        catch (e: Exception){
            e.printStackTrace()
            backupErrors.add("APP_BACKUP_TRY_CATCH$errorTag: ${e.message}")
        }
    }

    private fun cancelTask() {
        if (BACKUP_PID != -999) {
            commonTools.tryIt {
                val killProcess = Runtime.getRuntime().exec("su")

                val writer = BufferedWriter(OutputStreamWriter(killProcess.outputStream))
                writer.write("kill -9 $BACKUP_PID\n")
                writer.write("kill -15 $BACKUP_PID\n")
                writer.write("exit\n")
                writer.flush()

                commonTools.tryIt { killProcess.waitFor() }
                commonTools.tryIt { suProcess?.waitFor() }

                Toast.makeText(engineContext, engineContext.getString(R.string.deletingFiles), Toast.LENGTH_SHORT).show()

                if (bd.totalParts > 1) {
                    commonTools.dirDelete(actualDestination)
                    commonTools.dirDelete("$actualDestination.zip")
                } else {
                    commonTools.dirDelete(bd.destination)
                }
            }
        }
    }

    override fun onPreExecute() {
        super.onPreExecute()
        backupDependencyComponent.inject(this)

        if (bd.partNumber == 0){
            var previousBackupScripts = engineContext.filesDir.listFiles {
                f -> (f.name.startsWith(FILE_PREFIX_BACKUP_SCRIPT) || f.name.startsWith(FILE_PREFIX_RETRY_SCRIPT)) &&
                    f.name.endsWith(".sh")
            }
            for (f in previousBackupScripts) f.delete()

            engineContext.externalCacheDir?.let {
                previousBackupScripts = it.listFiles {
                    f -> (f.name.startsWith(FILE_PREFIX_BACKUP_SCRIPT) || f.name.startsWith(FILE_PREFIX_RETRY_SCRIPT)) &&
                        f.name.endsWith(".sh")
                }
                for (f in previousBackupScripts) f.delete()
            }
        }
    }

    override fun doInBackground(vararg params: Any?): Any {
        val scriptLocation = makeBackupScript()
        scriptLocation?.let { runBackupScript(it) }
        return 0
    }

    override fun onPostExecute(result: Any?) {
        super.onPostExecute(result)
        BACKUP_PID = -999
        if (backupErrors.size == 0)
            onBackupComplete.onBackupComplete(jobcode, true, bd.partNumber)
        else onBackupComplete.onBackupComplete(jobcode, false, backupErrors)
    }

    override fun onCancelled() {
        super.onCancelled()
        isBackupCancelled = true
        cancelTask()
    }
}