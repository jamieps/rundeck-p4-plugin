package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.jobs.JobReference
import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import com.google.common.io.ByteStreams
import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.file.FileAction
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.generic.core.file.FileSpec
import com.perforce.p4java.option.server.GetFileContentsOptions
import org.apache.log4j.Logger
import org.rundeck.plugin.scm.p4.config.Import
import org.rundeck.plugin.scm.p4.imp.actions.ImportJobs
import org.rundeck.plugin.scm.p4.imp.actions.PullAction
import org.rundeck.plugin.scm.p4.imp.actions.SetupTracking
import org.rundeck.plugin.scm.p4.imp.actions.SyncAction

/**
 * Import jobs from Perforce
 */
class P4ImportPlugin extends BaseP4Plugin implements ScmImportPlugin {
    static final Logger log = Logger.getLogger(P4ImportPlugin)
    public static final String ACTION_INITIALIZE_TRACKING = 'initialize-tracking'
    public static final String ACTION_IMPORT_ALL = 'import-all'
    public static final String ACTION_PULL = 'remote-pull'
    public static final String ACTION_SYNC = 'remote-sync'
    boolean inited
    boolean trackedItemsSelected = false
    boolean useTrackingRegex = false
    String trackingRegex
    List<String> trackedItems = null
    Import config
    /**
     * path -> commitId, tracks which commits were imported, if path has a newer commit ID, then
     * it needs to be imported.
     */
    ImportTracker importTracker = new ImportTracker()

    protected Map<String, P4ImportAction> actions = [:]

    P4ImportPlugin(final Import config, List<String> trackedItems) {
        super(config)
        this.config=config
        this.trackedItems = trackedItems
    }

    @Override
    ScmExportResult scmImport(
            final ScmOperationContext context,
            final String actionId,
            final JobImporter importer,
            final List<String> selectedPaths,
            final Map<String, String> input
    ) throws ScmPluginException
    {
        return actions[actionId]?.performAction(context, this, importer, selectedPaths, input)
    }

    void initialize(final ScmOperationContext context) {
        setup(context)
        actions = [
                (ACTION_INITIALIZE_TRACKING): new SetupTracking(
                        ACTION_INITIALIZE_TRACKING,
                        "Select Files to Import",
                        "Choose files and options for importing",
                        "glyphicon-cog"

                ),
                (ACTION_IMPORT_ALL)         : new ImportJobs(
                        ACTION_IMPORT_ALL,
                        "Import Remote Changes",
                        "Import Changes",
                        null

                ),

                (ACTION_PULL)               : new PullAction(
                        ACTION_PULL,
                        "Pull Remote Changes",
                        "Synch incoming changes from Remote"
                ),

                (ACTION_SYNC)               : new SyncAction(
                        ACTION_SYNC,
                        "Sync Remote Changes",
                        "Sync changes from Remote for local comparison"
                ),

        ]
    }

    void setup(final ScmOperationContext context) throws ScmPluginException {

        if (inited) {
            log.debug("already initialised, not doing setup")
            return
        }

        File base = new File(config.dir)
        mapper = new TemplateJobFileMapper(expand(config.pathTemplate, [format: config.format], "config"), base)

        cloneOrCreate(context, base, config.uri)

        workingDir = base

        SetupTracking.setupWithInput(this, this.trackedItems, config.rawInput)

        inited = true
    }


    @Override
    void cleanup() {
        p4Server?.disconnect()
    }


    @Override
    ScmImportSynchState getStatus(ScmOperationContext context) {
        return getStatusInternal(context, config.shouldFetchAutomatically())
    }


    P4ImportSynchState getStatusInternal(ScmOperationContext context, boolean performFetch) {
        // Look for any un-imported paths
        if (!trackedItemsSelected) {
            return null
        }

        def msgs = []
        if (performFetch) {
            try {
                fetchFromRemote(context)
            } catch (Exception e) {
                msgs<<"Fetch from the repository failed: ${e.message}"
                logger.error("Failed fetch from the repository: ${e.message}")
                logger.debug("Failed fetch from the repository: ${e.message}", e)
            }
        }

        int importNeeded = 0
        int notFound = 0
        int deleted = 0
        log.debug("import tracker: ${importTracker}")
        Set<String> expected = new HashSet(importTracker.trackedPaths())
        Set<String> newItems = new HashSet()
        Set<String> renamed  = new HashSet()

        p4Server.getCurrentClient().haveList(null).each {
            IFileSpec fileSpec ->
                String localPath    = fileSpec.getLocalPathString()
                if (expected.contains(localPath)) {
                    // existing tracked item
                    expected.remove(localPath)
                    if (trackedItemNeedsImport(localPath)) {
                        importNeeded++
                    }
                } else if (importTracker.wasRenamed(localPath)) {
                    // item tracked to a job which was renamed
                    expected.remove(importTracker.renamedValue(localPath))
                    renamed.add(localPath)
                } else if (importTracker.trackedItemIsUnknown(localPath)) {
                    // path is new and needs to be imported
                    newItems.add(localPath)
                    notFound++
                }
        }
        // Find any paths we are tracking that are no longer present
        if (expected) {
            // Deleted paths
            deleted = expected.size()
            log.debug("deleted files ${expected}")
        }
        def state = new P4ImportSynchState()
        state.importNeeded = importNeeded
        state.notFound = notFound
        state.deleted = deleted

        if (importNeeded || renamed || notFound) {
            state.state = ImportSynchState.IMPORT_NEEDED
        } else if (deleted) {
            state.state = ImportSynchState.DELETE_NEEDED
        } else {
            state.state = ImportSynchState.CLEAN
        }

        if (importNeeded) {
            msgs << "${importNeeded} file(s) need to be imported"
        }
        if (renamed) {
            msgs << "${renamed.size()} file(s) were renamed"
        }
        if (notFound) {
            msgs << "${notFound} unimported file(s) found"
        }
        if (deleted) {
            msgs << "${deleted} tracked file(s) were deleted"
        }
        state.message = msgs.join(', ')
        return state
    }

    private hasJobStatusCached(final JobScmReference job, final String originalPath) {
//        def path = relativePath(job)
//
//        def commit = P4Util.lastCommitForPath repo, p4, path
//
//        def ident = job.id + ':' + String.valueOf(job.version) + ':' + (commit ? commit.name : '')
//
//        if (jobStateMap[job.id] && jobStateMap[job.id].ident == ident) {
//            log.debug("hasJobStatusCached(${job.id}): FOUND")
//            return jobStateMap[job.id]
//        }
//        log.debug("hasJobStatusCached(${job.id}): (no)")

        null
    }

    private refreshJobStatus(final JobScmReference job, final String originalPath) {

        def previousImportCommit = job.scmImportMetadata?.commitId ?
                P4Util.getCommit(p4Server, job.scmImportMetadata.commitId) : null

        def path = relativePath(job)

        jobStateMap.remove(job.id)

        def jobStat = Collections.synchronizedMap([:])
        def latestCommit = P4Util.lastCommitForPath p4Server, path

//        log.debug(debugStatus(status))
        ImportSynchState synchState = importSynchStateForStatus(job, p4Server.getChangelist(latestCommit.getId()), path)

        if (originalPath && synchState == ImportSynchState.UNKNOWN) {
            //job was renamed but not file
            synchState = ImportSynchState.IMPORT_NEEDED
        } else if (job.scmImportMetadata?.commitId) {
            //update tracked commit info
            importTracker.trackJobAtPath(job, path)
        }
        log.debug(
                "import job status: ${synchState} with meta ${job.scmImportMetadata}, version ${job.importVersion}/${job.version} commit ${latestCommit?.name}"
        )

        def ident = job.id + ':' + String.valueOf(job.version) + ':' + (latestCommit ? latestCommit.name : '')

        jobStat['ident'] = ident
        jobStat['id'] = job.id
        jobStat['version'] = job.version
        jobStat['synch'] = synchState
        jobStat['path'] = path
        if (previousImportCommit) {
            jobStat['commitId'] = previousImportCommit.name
            jobStat['commitMeta'] = P4Util.metaForCommit(previousImportCommit)
        }
        log.debug("refreshJobStatus(${job.id}): ${jobStat}")

        jobStateMap[job.id] = jobStat

        jobStat
    }


    private ImportSynchState importSynchStateForStatus(
            JobScmReference job,
            IChangelist commit,
            String path
    )
    {
        if (!isTrackedPath(path) || !commit) {
            //not tracked
            return ImportSynchState.UNKNOWN
        }
        if (job.scmImportMetadata && job.scmImportMetadata.commitId == commit.getId()) {
            if (job.importVersion == job.version) {
                return ImportSynchState.CLEAN
            } else {
                log.debug("job version differs, fall back to content diff")
                // Serialize job and determine if there is a difference
                if (contentDiffers(job, commit, path)) {
                    return ImportSynchState.IMPORT_NEEDED
                } else {
                    return ImportSynchState.CLEAN
                }
            }
        } else {
            if (job.scmImportMetadata && job.scmImportMetadata.commitId && commit &&
                    (!job.scmImportMetadata.url || job.scmImportMetadata.url == config.uri)) {
                // Determine change between tracked commit ID and head commit, if available
                // i.e. detect if path was deleted
                def changes = P4Util.listChanges(p4Server, job.scmImportMetadata.commitId)

                FileAction changeType   = FileAction.IGNORED

                def editedFiles = changes.findAll { it["action0"].equals(FileAction.EDIT.toString()) }
                def movedFiles  = changes.findAll { it["action0"].equals(FileAction.MOVE_DELETE.toString()) ||
                        it["action0"].equals(FileAction.MOVE_ADD.toString()) }
                movedFiles.each({ change ->
                    String depotFile = change["depotFile"]
                    switch (change["action0"]) {
                    case FileAction.MOVE_DELETE.toString():
                        String movedTo = change["file1,0"]
                        List<IFileSpec> fileSpecs =
                                p4Client.where(FileSpecBuilder.makeFileSpecList(depotFile, movedTo))
                        def srcSpec = fileSpecs[0]
                        def destSpec = fileSpecs[1]
                        if (destSpec.getLocalPath() == null) {
                            // No longer in client view
                            println "${depotFile} has moved out of this client"
                            changeType = FileAction.DELETED
                        } else {
                            // Moved within client
                            println "${depotFile} (${fileSpecs[0].getLocalPath()}) moved -> ${movedTo} (${fileSpecs[1].getLocalPath()})"
                            changeType = FileAction.MOVE
                        }
                        break
                    case FileAction.MOVE_ADD.toString():
                        String movedFrom = change["file0,0"]
                        List<IFileSpec> fileSpecs =
                                p4Client.where(FileSpecBuilder.makeFileSpecList(movedFrom, depotFile))
                        def srcSpec = fileSpecs[0]
                        def destSpec = fileSpecs[1]
                        if (srcSpec.getLocalPath() == null) {
                            // New in client
                            println "${depotFile} is a new addition"
                            changeType = FileAction.ADDED
                        } else {
                            // Moved within client
                            println "${depotFile} has moved within client, will process move/delete instead"
                        }
                        break
                    }
                })

                if (movedFiles && changeType == FileAction.DELETED) {
                    return ImportSynchState.DELETE_NEEDED
                } else if (editedFiles) {
                    return ImportSynchState.IMPORT_NEEDED
                } else if (movedFiles && changeType == FileAction.MOVE) {
                    return ImportSynchState.IMPORT_NEEDED
                }
            }
            // different commit was imported previously, or job has been modified
            return ImportSynchState.IMPORT_NEEDED
        }
    }

    boolean contentDiffers(final JobScmReference job, IChangelist commit, final String path) {
        def currentJob = new ByteArrayOutputStream()
        job.jobSerializer.serialize(path.endsWith('.xml') ? 'xml' : 'yaml', currentJob)

        IFileSpec fileSpec = p4Client.where(FileSpecBuilder.makeFileSpecList(path)).get(0)
        byte[] bytes = ByteStreams.toByteArray(fileSpec.getContents(new GetFileContentsOptions().setNoHeaderLine(true)))

        def diffCount = diffContent(null, currentJob.toByteArray(), bytes)
        log.debug("diffContent: found ${diffCount} changes for ${path}")
        return diffCount > 0
    }

    @Override
    JobImportState getJobStatus(final JobScmReference job) {
        return getJobStatus(job, null)
    }

    @Override
    JobImportState getJobStatus(final JobScmReference job, String originalPath) {
        log.debug("getJobStatus(${job.id},${originalPath})")
        def path = relativePath(job)
        if (null == originalPath) {
            originalPath = importTracker.originalValue(path)
        }
        def status = hasJobStatusCached(job, originalPath)
        if (!status) {
            status = refreshJobStatus(job, originalPath)
        }
        return createJobImportStatus(status,jobActionsForStatus(status))
    }

    List<Action> jobActionsForStatus(Map status) {
        if (status.synch == ImportSynchState.IMPORT_NEEDED) {
            [actions[ACTION_IMPORT_ALL]]
        } else {
            []
        }
    }


    @Override
    JobImportState jobChanged(JobChangeEvent event, JobScmReference reference) {
        def path = relativePath(event.originalJobReference)
        def newpath = relativePath(event.jobReference)
        String origPath = null
        if (!isTrackedPath(path) && !isTrackedPath(newpath)) {
            return null
        }
        log.debug("Job event (${event.eventType}), path: ${path}")
        switch (event.eventType) {
            case JobChangeEvent.JobChangeEventType.DELETE:
                importTracker.untrackPath(path)

                def status = [synch: ImportSynchState.IMPORT_NEEDED]
                return createJobImportStatus(status,jobActionsForStatus(status))
                break;

            case JobChangeEvent.JobChangeEventType.MODIFY_RENAME:
                importTracker.jobRenamed(reference, path, newpath)
        //TODO
//            case JobChangeEvent.JobChangeEventType.CREATE:
//            case JobChangeEvent.JobChangeEventType.MODIFY:

        }
//        def status = refreshJobStatus(reference, origPath)
//        return createJobImportStatus(status)
        null
    }


    @Override
    BasicInputView getInputViewForAction(final ScmOperationContext context, final String actionId) {
        return actions[actionId]?.getInputView(context, this)
    }

    @Override
    Action getSetupAction(ScmOperationContext context) {
        if (!trackedItemsSelected) {
            return actions[ACTION_INITIALIZE_TRACKING]
        }
        null
    }

    @Override
    List<Action> actionsAvailableForContext(ScmOperationContext context) {
        if (context.frameworkProject) {
            //project-level actions
            if (!trackedItemsSelected) {
                return [actions[ACTION_INITIALIZE_TRACKING]]
            } else {

                def avail = []
                def status = getStatusInternal(context, false)
                if (status.state == ImportSynchState.REFRESH_NEEDED) {
                    avail << actions[ACTION_PULL]
                }
                if (status.state != ImportSynchState.CLEAN) {
                    avail << actions[ACTION_IMPORT_ALL]
                }
                if (!config.shouldSyncAutomatically()){
                    avail << actions[ACTION_SYNC]
                }
                return avail
            }
        }
        return null
    }

    @Override
    String getRelativePathForJob(final JobReference job) {
        relativePath(job)
    }


    @Override
    ScmImportDiffResult getFileDiff(final JobScmReference job) {
        return getFileDiff(job, null)

    }

    @Override
    ScmImportDiffResult getFileDiff(final JobScmReference job, String originalPath) {
        def path = relativePath(job)
        if (!originalPath) {
            originalPath = importTracker.originalValue(path)
        }
        path = originalPath ?: relativePath(job)
        def temp = serializeTemp(job, config.format)
        def latestCommit = P4Util.lastCommitForPath p4Server, path
        if (!latestCommit) {
            return new P4DiffResult(newNotFound: true)
        }
        IFileSpec fileSpec = new FileSpec(path)
        fileSpec.setServer(p4Server)
        def bytes = fileSpec.getContents(new GetFileContentsOptions().setNoHeaderLine(true)).getBytes()
        def baos = new ByteArrayOutputStream()
        def diffs = diffContent(baos, temp, bytes)
        temp.delete()


        def availableActions = diffs > 0 ? [actions[ACTION_IMPORT_ALL]] : null
        return new P4DiffResult(
                content: baos.toString(),
                modified: diffs > 0,
                incomingCommit: new P4ScmCommit(P4Util.metaForCommit(latestCommit)),
                actions: availableActions
        )
    }

    @Override
    List<ScmImportTrackedItem> getTrackedItemsForAction(final String actionId) {
        if (actionId == ACTION_INITIALIZE_TRACKING) {
            if (!trackedItems) {
                List<ScmImportTrackedItem> found = []

                // Walk the repo files and look for possible candidates
                p4Client.haveList(null).each {
                    IFileSpec fileSpec ->
                        found << fileSpec.getLocalPathString()
                }
                return found
            } else {
                //list
                return trackedItems.collect {
                    trackPath(it, false, importTracker.trackedJob(it))
                }
            }
        } else if (actionId == ACTION_IMPORT_ALL) {

            List<ScmImportTrackedItem> found = []

            // Walk the repo files and look for possible candidates
            p4Client.haveList(null).each {
                IFileSpec fileSpec ->
                    found << trackPath(fileSpec.getLocalPathString(),
                        trackedItemNeedsImport(fileSpec.getLocalPathString()),
                        importTracker.trackedJob(fileSpec.getLocalPathString()))
            }
            return found
        }
        null
    }

    /**
     * Return true if the path last imported commit does not match the latest commit
     * @param path
     * @return true if changes need to be imported
     */
    private boolean trackedItemNeedsImport(String path) {
        def commit = lastCommitForPath(path)
        commit.name != importTracker.trackedCommit(path)
    }


    ScmImportTrackedItem trackPath(final String path, final boolean selected = false, String jobId = null) {
        ScmImportTrackedItemBuilder.builder().
                id(path).
                iconName('glyphicon-file').
                selected(selected).
                jobId(jobId).
                build()
    }


    boolean isTrackedPath(final String path) {
        return trackedItems?.contains(path) || isUseTrackingRegex() && trackingRegex && path.matches(trackingRegex)
    }
}
