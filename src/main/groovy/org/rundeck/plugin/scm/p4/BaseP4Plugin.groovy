package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.jobs.JobReference
import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.core.storage.ResourceMeta
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.IChangelistSummary
import com.perforce.p4java.core.IMapEntry
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.FileSpecOpStatus
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.generic.client.ClientView
import com.perforce.p4java.impl.mapbased.client.Client
import com.perforce.p4java.option.client.ResolveFilesAutoOptions
import com.perforce.p4java.option.client.SyncOptions
import com.perforce.p4java.server.IServer
import com.perforce.p4java.server.ServerFactory
import org.apache.log4j.Logger
import org.rundeck.plugin.scm.p4.config.Common
import org.rundeck.storage.api.StorageException

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Common features of the Perforce import and export plugins.
 */
class BaseP4Plugin {
    IServer p4Server
    IClient p4Client
    File workingDir
    String depotPath
    Map<String, String> input
    Common commonConfig
    JobFileMapper mapper
    /**
     * Standard resolve options. This is equivalent to p4 resolve -am (resolve by merging, skip conflicts).
     */
    final static ResolveFilesAutoOptions RESOLVE_OPTIONS = new ResolveFilesAutoOptions().setSafeMerge(false)
            .setAcceptTheirs(false).setAcceptYours(false).setForceResolve(false)

    Map<String, Map> jobStateMap = Collections.synchronizedMap([:])

    BaseP4Plugin(Common commonConfig) {
        this.input = commonConfig.rawInput
        this.commonConfig = commonConfig
    }
    
    /**
     * maps output file to an AtomicLong used for synchronization and
     * only serializing monotonically increasing revision for the job
     */
    ConcurrentMap<File, AtomicLong> fileSerializeRevisionCounter = new ConcurrentHashMap<>()

    /**
     * Get an AtomicLong used for synchronization and comparing
     * serialized revision number of the file.
     *
     * @param outfile the target file
     * @return atomic long for file serialization revision number
     */
    private AtomicLong fileCounterFor(File outfile) {
        AtomicLong latch = fileSerializeRevisionCounter.get(outfile)
        if (latch == null) {
            latch = new AtomicLong(-1)
            AtomicLong previous = fileSerializeRevisionCounter.putIfAbsent(outfile, latch)
            if (null != previous) {
                latch = previous
            }
        }
        latch
    }

    /**
     * compareAndSet if the new value is greater than the current value
     * @param atomic atomic long
     * @param update the new value
     * @return
     */
    static boolean greaterAndSet(AtomicLong atomic, long update) {
        while (true) {
            long cur = atomic.get();
            if (update <= cur) {
                return false
            }
            //should set if it hasn't changed
            if (atomic.compareAndSet(cur, update)) {
                return true;
            }
            //otherwise try again
        }
    }

    def serialize(final JobExportReference job, format, File outfile = null) {
        if (!outfile) {
            outfile = mapper.fileForJob(job)
        }
        AtomicLong counter = fileCounterFor(outfile)
        logger.debug("Start serialize[${Thread.currentThread().name}]...")

        synchronized (counter) {
            //other threads serializing the same job must wait until we complete
            if (greaterAndSet(counter, job.version)) {
                //only bother writing the file if this rev of Job if it is newer than previously serialized rev

                if (!outfile.parentFile.isDirectory()) {
                    if (!outfile.parentFile.mkdirs()) {
                        throw new ScmPluginException(
                                "Cannot create necessary dirs to serialize file to path: ${outfile.absolutePath}"
                        )
                    }
                }

                File temp = new File(outfile.parentFile, outfile.name + ".tmp${job.version}")
                temp.deleteOnExit()
                Throwable thrown = null
                try {
                    try {
                        temp.withOutputStream { out ->
                            try {
                                job.jobSerializer.serialize(format, out)
                            } catch (Throwable e) {
                                thrown = e;
                            }
                        }
                    } catch (IOException e) {
                        throw new ScmPluginException("Failed to serialize job ${job}: ${e.message}", e)
                    }
                    if (thrown != null) {
                        throw new ScmPluginException("Failed to serialize job ${job}: ${thrown.message}", thrown)
                    }
                    if (!temp.exists() || temp.size() < 1) {
                        throw new ScmPluginException(
                                "Failed to serialize job, no content was written for job ${job}"
                        )
                    }
                    logger.debug("Serialized[${Thread.currentThread().name}] ${job} ${format} to ${outfile}")

                    Files.move(temp.toPath(), outfile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    if (temp.exists()) {
                        temp.delete()
                    }
                }
            } else {
                //another thread already serialized this or earlier revision of the job, should not
                logger.debug("SKIP serialize[${Thread.currentThread().name}] for ${job} to ${outfile}")
            }
        }
        logger.debug("Done serialize[${Thread.currentThread().name}]...")
    }

    def serializeTemp(final JobExportReference job, format) {
        File outfile = File.createTempFile("${this.class.name}-serializeTemp", ".${format}")
        outfile.deleteOnExit()
        outfile.withOutputStream { out ->
            job.jobSerializer.serialize(format, out)
        }
        return outfile
    }

    def serializeAll(final Set<JobExportReference> jobExportReferences, String format) {
        jobExportReferences.each { serialize(it, format) }
    }

    List<IFileSpec> fetchFromRemote(ScmOperationContext context) {
        p4Client.sync([], new SyncOptions())
    }

    ScmExportResult p4Resolve() {
        def result      = new ScmExportResultImpl()
        result.success  = true

        // Resolve all files which require it
        List<IFileSpec> results = p4Client.resolveFilesAuto(null, RESOLVE_OPTIONS)
        results.each({ fileSpec ->
            if (fileSpec.getOpStatus() == FileSpecOpStatus.ERROR) {
                // A problem occurred resolving this file.
                result.success  = false
                String msg      = fileSpec.getDepotPath() + " resolve failed: " + fileSpec.getStatusMessage()
                if (result.extendedMessage.isEmpty()) {
                    result.extendedMessage = msg
                } else {
                    result.extendedMessage = result.extendedMessage + ", " + msg
                }
            }
        })

        result.message = result.success ? "Rebase was successful" : "Rebase did not succeed"
        result
    }

    File getLocalFileForJob(final JobReference job) {
        mapper.fileForJob(job)
    }

    String relativePath(JobReference reference) {
        mapper.pathForJob(reference)
    }
    /**
     * Get the HEAD revision for the client
     * @return changelist summary or null if HEAD not found (empty repo)
     */
    IChangelistSummary getHead() {
        List<IChangelistSummary> changes = p4Server.getChangelists(1, FileSpecBuilder.makeFileSpecList("//..."),
                p4Client.getName(), null, true, IChangelist.Type.SUBMITTED, false)
        if (changes.isEmpty()) {
            return null
        }
        return changes.get(0)
    }

    int diffContent(OutputStream out, byte[] left, File right) {
        P4Util.diffContent out, left, right
    }

    int diffContent(OutputStream out, File left, byte[] right) {
        P4Util.diffContent out, left, right
    }

    int diffContent(OutputStream out, byte[] left, byte[] right) {
        P4Util.diffContent out, left, right
    }

    IChangelistSummary lastCommitForPath(String path) {
        List<IChangelistSummary> changes = p4Server.getChangelists(1, FileSpecBuilder.makeFileSpecList(path),
                p4Client.getName(), null, true, IChangelist.Type.SUBMITTED, false)
        if (changes.isEmpty()) {
            return null
        }
        return changes.get(0)
    }

    static String expand(final String source, final ScmUserInfo scmUserInfo) {
        def userInfoProps = ['fullName', 'firstName', 'lastName', 'email', 'userName']
        def map = userInfoProps.collectEntries { [it, scmUserInfo[it]] }
        map['login'] = map['userName']
        expand(source, map, 'user')
    }

    static String expand(final String source, final Map<String, String> data, String prefix = '') {
        data.keySet().inject(source) { String x, String y ->
            return x.replaceAll(
                    Pattern.quote('${' + (prefix ? prefix + '.' : '') + y + '}'),
                    Matcher.quoteReplacement(data[y] ?: '')
            )
        }
    }

    JobState createJobStatus(final Map map, final List<Action> actions = []) {
        //TODO: include scm status
        return new JobP4State(
                synchState: map['synch'],
                commit: map.commitMeta ? new P4ScmCommit(map.commitMeta) : null,
                actions: actions
        )
    }

    JobImportState createJobImportStatus(final Map map, final List<Action> actions = []) {
        //TODO: include scm status
        return new JobImportP4State(
                synchState: map['synch'],
                commit: map.commitMeta ? new P4ScmCommit(map.commitMeta) : null,
                actions: actions
        )
    }

    Logger getLogger() {
        Logger.getLogger(this.class)
    }

    private InputStream getStoragePathStream(final ScmOperationContext context, String path) throws IOException {
        if (null == path) {
            return null;
        }
        def tree = context.getStorageTree()
        if (!tree.hasResource(path)) {
            throw new ScmPluginException("Path does not exist: ${path}")
        }
        ResourceMeta contents
        try {
            contents = tree.getResource(path).getContents()
        } catch (StorageException e) {
            logger.debug("getStoragePathStream", e)
            throw new ScmPluginException(e)
        }
        return contents.getInputStream()
    }

    private byte[] loadStoragePathData(final ScmOperationContext context, String path)
            throws IOException, ScmPluginException
    {
        if (null == path) {
            return null;
        }

        def tree = context.getStorageTree()
        if (!tree.hasResource(path)) {
            throw new ScmPluginException("Path does not exist: ${path}")
        }
        ResourceMeta contents
        try {
            contents = tree.getResource(path).getContents()
        } catch (StorageException e) {
            logger.debug("loadStoragePathData", e)
            throw new ScmPluginException(e)
        }
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        contents.writeContent(byteArrayOutputStream);
        return byteArrayOutputStream.toByteArray();
    }

    protected void cloneOrCreate(final ScmOperationContext context, File rootDir, String uri) throws ScmPluginException {
        p4Server = connect(uri)
        p4Client = getOrCreateClient(commonConfig.p4ClientName, rootDir)
        // Sync files to #head - with -f to ensure client matches depot
        p4Client.sync(FileSpecBuilder.makeFileSpecList(),
                new SyncOptions().setForceUpdate(true))
    }

    private static String collectCauseMessages(Exception e) {
        List<String> msgs = [e.message]
        def cause = e.cause
        while (cause) {
            if (cause.message != msgs.last() && !msgs.last().endsWith(cause.message)) {
                msgs << cause.message
            }
            cause = cause.cause
        }
        return msgs.join("; ")
    }

    /**
     * Connect to Perforce via a given URI, return the resulting IServer instance.
     *
     * @param   uri the URI to connect using
     * @return  the IServer instance, or null if the connection did not succeed
     */
    private IServer connect(String uri) {
        IServer srv = ServerFactory.getServer(uri, null)
        srv.connect()
        srv
    }

    /**
     * Find a Perforce client by name, create it if it does not already exist.
     *
     * @param       clientName  the name of the Perforce client
     * @return      the IClient instance of the client
     */
    private IClient getOrCreateClient(String clientName, File rootDir) {
        // Check if the client exists first
        IClient cli = p4Server.getClient(clientName)
        if (cli == null) {
            // Client does not exist, let's create it
            logger.info("Creating Perforce client ${clientName} in ${rootDir.getAbsolutePath()}")
            cli = new Client(name: clientName, server: p4Server,
                    root: rootDir.getAbsolutePath(),
                    description: "Created by rundeck-p4-plugin")

            ClientView.ClientViewMapping cliViewEntry = new ClientView.ClientViewMapping()
            cliViewEntry.setLeft(depotPath)
            cliViewEntry.setLeft(commonConfig.p4Spec)
            cliViewEntry.setRight("//" + clientName + "/...")
            cliViewEntry.setType(IMapEntry.EntryType.INCLUDE)
            ClientView cliView = new ClientView()
            cliView.addEntry(cliViewEntry)
            cli.setClientView(cliView)

            p4Server.createClient(cli)
            cli = p4Server.getClient(clientName)
            p4Server.setCurrentClient(cli)
        }

        cli
    }

    /**
     * Expand variable references in the storage path, such as ${user.name} and ${project}* @param context
     * @param path
     * @return
     */
    public static String expandContextVarsInPath(ScmOperationContext context, String path) {
        expand(expand(path, context.userInfo), [project: context.frameworkProject])
    }
}
