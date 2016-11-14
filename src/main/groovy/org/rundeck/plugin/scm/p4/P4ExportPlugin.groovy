package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.jobs.JobReference
import com.dtolabs.rundeck.core.jobs.JobRevReference
import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.core.plugins.views.ActionBuilder
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.core.IChangelistSummary
import com.perforce.p4java.core.file.*
import com.perforce.p4java.impl.generic.core.file.FileSpec
import com.perforce.p4java.option.client.ReconcileFilesOptions
import com.perforce.p4java.option.client.SyncOptions
import com.perforce.p4java.option.server.OpenedFilesOptions
import org.apache.log4j.Logger
import org.rundeck.plugin.scm.p4.config.Export
import org.rundeck.plugin.scm.p4.exp.actions.CommitJobsAction
import org.rundeck.plugin.scm.p4.exp.actions.FetchAction
import org.rundeck.plugin.scm.p4.exp.actions.LabelAction
import org.rundeck.plugin.scm.p4.exp.actions.SynchAction

/**
 * Perforce export plugin
 */
class P4ExportPlugin extends BaseP4Plugin implements ScmExportPlugin {
    static final Logger log = Logger.getLogger(P4ExportPlugin)
    public static final String SERIALIZE_FORMAT         = 'xml'

    public static final String JOB_COMMIT_ACTION_ID     = "job-commit"
    public static final String PROJECT_COMMIT_ACTION_ID = "project-commit"
    public static final String PROJECT_PUSH_ACTION_ID   = "project-push"
    public static final String PROJECT_TAG_ACTION_ID    = "tag-commit"
    public static final String PROJECT_SYNCH_ACTION_ID  = "project-synch"
    public static final String PROJECT_FETCH_ACTION_ID  = "project-fetch"


    String format = SERIALIZE_FORMAT
    boolean inited = false
    String committerName;
    String committerEmail;
    Map<String, P4ExportAction> actions = [:]
    Export config

    P4ExportPlugin(Export config) {
        super(config)
        this.config = config
    }

    void initialize(ScmOperationContext context) {
        setup(context, config)
        actions = [
                (JOB_COMMIT_ACTION_ID)    : new CommitJobsAction(
                        JOB_COMMIT_ACTION_ID,
                        "Commit Changes to Perforce",
                        "Commit changes to local Perforce repo."
                ),
                (PROJECT_COMMIT_ACTION_ID): new CommitJobsAction(
                        PROJECT_COMMIT_ACTION_ID,
                        "Commit Changes to Perforce",
                        "Commit changes to Perforce repo."
                ),
                (PROJECT_SYNCH_ACTION_ID) : new SynchAction(
                        PROJECT_SYNCH_ACTION_ID,
                        "Synch with Remote",
                        "Synch incoming changes from Remote"
                ),
                (PROJECT_FETCH_ACTION_ID) : new FetchAction(
                        PROJECT_FETCH_ACTION_ID,
                        "Fetch Remote Changes",
                        "Fetch changes from Remote for local comparison"
                ),
                (PROJECT_TAG_ACTION_ID)   : new LabelAction(
                        PROJECT_TAG_ACTION_ID,
                        "Create Label",
                        "Label commit"
                ),

        ]
    }

    void setup(ScmOperationContext context, Export config) throws ScmPluginException {

        if (inited) {
            log.debug("already inited, not doing setup")
            return
        }

        format = config.format ?: 'xml'

        if (!(format in ['xml', 'yaml'])) {
            throw new IllegalArgumentException("format cannot be ${format}, must be one of: xml,yaml")
        }

        committerName = config.committerName
        committerEmail = config.committerEmail
        File base = new File(config.dir)
        mapper = new TemplateJobFileMapper(expand(config.pathTemplate, [format: config.format], "config"), base)
        cloneOrCreate(context, base, config.uri)

        workingDir = base
        inited = true
    }

    @Override
    void cleanup() {
        p4Server?.disconnect()
    }


    @Override
    BasicInputView getInputViewForAction(final ScmOperationContext context, String actionId) {
        actions[actionId]?.getInputView(context, this)
    }


    @Override
    ScmExportResult export(
            final ScmOperationContext context,
            final String actionId,
            final Set<JobExportReference> jobs,
            final Set<String> pathsToDelete,
            final Map<String, String> input
    )
            throws ScmPluginException
    {
        if (!actions[actionId]) {
            throw new ScmPluginException("Unexpected action ID: " + actionId)
        }
        actions[actionId].perform(this, jobs, pathsToDelete, context, input)
    }


    @Override
    List<String> getDeletedFiles() {
        List<IFileSpec> fileStatus = p4Client.reconcileFiles(FileSpecBuilder.makeFileSpecList("//..."),
                new ReconcileFilesOptions().setNoUpdate(true).setRemoved(true))
        return fileStatus.collect { it.getLocalPathString() }
    }

    protected List<Action> actionRefs(String... ids) {
        actions.subMap(Arrays.asList(ids)).values().collect { ActionBuilder.from(it) }
    }

    @Override
    List<Action> actionsAvailableForContext(ScmOperationContext context) {
        if (context.jobId) {
            //todo: get job status to determine actions
//            actionRefs JOB_COMMIT_ACTION_ID
            null
        } else if (context.frameworkProject) {
            //actions in project view
            def status = getStatusInternal(context, false)
            if (!status.gitStatus.clean) {
                actionRefs PROJECT_COMMIT_ACTION_ID
            } else if (status.state == SynchState.EXPORT_NEEDED) {
                //need a push
                actionRefs PROJECT_PUSH_ACTION_ID
            } else if (status.state == SynchState.REFRESH_NEEDED) {
                //need to fast forward
                actionRefs PROJECT_SYNCH_ACTION_ID
            } else if(!config.shouldFetchAutomatically()){
                actionRefs PROJECT_FETCH_ACTION_ID
            }else{
                null
            }
        } else {
            null
        }
    }


    @Override
    ScmExportSynchState getStatus(ScmOperationContext context) {
        return getStatusInternal(context, config.shouldFetchAutomatically())
    }


    P4ExportSynchState getStatusInternal(ScmOperationContext context, boolean performFetch) {
        //perform fetch
        def msgs = []
        boolean fetchError = false
        if (performFetch) {
            try {
                fetchFromRemote(context)
            } catch (Exception e) {
                fetchError=true
                msgs << "Fetch from the repository failed: ${e.message}"
                logger.error("Failed fetch from the repository: ${e.message}")
                logger.debug("Failed fetch from the repository: ${e.message}", e)
            }
        }

        List<IFileSpec> fileStatus = p4Client.reconcileFiles(FileSpecBuilder.makeFileSpecList("//..."),
                new ReconcileFilesOptions().setNoUpdate(true))

        def synchState = new P4ExportSynchState()
        synchState.p4Status = fileStatus
        synchState.state = fileStatus.isEmpty() ? SynchState.CLEAN : SynchState.EXPORT_NEEDED
        if (!fileStatus.isEmpty()) {
            msgs << "Some changes have not been committed"
        }

        // if clean, check remote tracking status
        if (fileStatus.isEmpty()) {
            List<IFileSpec> syncFiles = p4Client.sync(FileSpecBuilder.makeFileSpecList("//..."),
                    new SyncOptions().setNoUpdate(true))
            if (!syncFiles.isEmpty() && fileStatus.isEmpty()) {
                synchState.state = SynchState.REFRESH_NEEDED
                //TODO: test if merge would fail
            } else if (!syncFiles.isEmpty()) {
                msgs << "${syncFiles.size()} changes need to be sync'd"
                synchState.state = SynchState.REFRESH_NEEDED
            }
        }
        synchState.message = msgs ? msgs.join(', ') : null
        if (fetchError && synchState.state == SynchState.CLEAN) {
            synchState.state = SynchState.REFRESH_NEEDED
        }

        return synchState
    }

    @Override
    JobState jobChanged(JobChangeEvent event, JobExportReference exportReference) {
        File origfile = mapper.fileForJob(event.originalJobReference)
        File outfile = mapper.fileForJob(event.jobReference)
        String origPath = null
        log.debug("Job event (${event}), writing to path: ${outfile}")
        switch (event.eventType) {
            case JobChangeEvent.JobChangeEventType.DELETE:
                origfile.delete()
                def status = refreshJobStatus(event.jobReference, origPath, false)
                jobStateMap.remove(event.jobReference.id)
                return createJobStatus(status, jobActionsForStatus(status))
                break;

            case JobChangeEvent.JobChangeEventType.MODIFY_RENAME:
                origPath = relativePath(event.originalJobReference)
            case JobChangeEvent.JobChangeEventType.CREATE:
            case JobChangeEvent.JobChangeEventType.MODIFY:
                if (origfile != outfile) {
                    origfile.delete()
                }
                try {
                    serialize(exportReference, format, outfile)
                } catch (Throwable t) {
                    getLogger().warn("Could not serialize job: ${t}", t)
                }
        }
        def status = refreshJobStatus(exportReference, origPath, false)
        return createJobStatus(status, jobActionsForStatus(status))
    }

    private hasJobStatusCached(final JobExportReference job, final String originalPath) {
        def path = relativePath(job)

        def commit = lastCommitForPath(path)

        String ident = createStatusCacheIdent(job, commit)

        if (jobStateMap[job.id] && jobStateMap[job.id].ident == ident) {
            log.debug("hasJobStatusCached(${ident}): FOUND")
            return jobStateMap[job.id]
        }
        log.debug("hasJobStatusCached(${ident}): (no)")

        null
    }

    private String createStatusCacheIdent(JobRevReference job, IChangelistSummary change) {
        def ident = job.id + ':' +
                String.valueOf(job.version) +
                ':' +
                (change ? change.getId() : '') +
                ":" +
                (getLocalFileForJob(job)?.exists())
        ident
    }

    private refreshJobStatus(final JobRevReference job, final String originalPath, boolean doSerialize = true) {
        def path = relativePath(job)

        jobStateMap.remove(job.id)

        def jobstat = Collections.synchronizedMap([:])
        def commit = lastCommitForPath(path)

        if (job instanceof JobExportReference && doSerialize) {
            serialize(job, format)
        }

        List<IFileSpec> checkPaths  = FileSpecBuilder.makeFileSpecList(path)
        if (originalPath) {
            checkPaths += new FileSpec(originalPath)
        }

        List<IFileSpec> reconcileStatus = p4Client.reconcileFiles(checkPaths,
                new ReconcileFilesOptions().setNoUpdate(true))
        List<IFileSpec> openedFiles     = p4Client.openedFiles(FileSpecBuilder.makeFileSpecList(),
                new OpenedFilesOptions())

        SynchState synchState = synchStateForStatus(reconcileStatus, openedFiles, commit, path)
        def scmState = scmStateForStatus(status, commit, path)
        log.debug("for new path: commit ${commit}, synch: ${synchState}, scm: ${scmState}")

        if (originalPath) {
            def origCommit = lastCommitForPath(originalPath)
            SynchState osynchState = synchStateForStatus(status, origCommit, originalPath)
            def oscmState = scmStateForStatus(status, origCommit, originalPath)
            log.debug("for original path: commit ${origCommit}, synch: ${osynchState}, scm: ${oscmState}")
            if (origCommit && !commit) {
                commit = origCommit
            }
            if (synchState == SynchState.CREATE_NEEDED && oscmState == 'DELETED') {
                synchState = SynchState.EXPORT_NEEDED
            }
        }

        def ident = createStatusCacheIdent(job, commit)
//job.id + ':' + String.valueOf(job.version) + ':' + (commit ? commit.name : '')

        jobstat['ident'] = ident
        jobstat['id'] = job.id
        jobstat['version'] = job.version
        jobstat['synch'] = synchState
        jobstat['scm'] = scmState
        jobstat['path'] = path
        if (commit) {
            jobstat['commitId'] = commit.name
            jobstat['commitMeta'] = P4Util.metaForCommit(commit)
        }
        log.debug("refreshJobStatus(${job.id}): ${jobstat}")

        jobStateMap[job.id] = jobstat

        jobstat
    }

    private SynchState synchStateForStatus(List<IFileSpec> reconcileStatus, List<IFileSpec> openedFiles,
                                           IChangelistSummary change, String path) {
        List<IFileSpec> untrackedNewFiles   = reconcileStatus.findAll { it.getAction() == FileAction.ADD }

        if (path && untrackedNewFiles.find { it.getLocalPathString().equals(path) } ||
                !path && !untrackedNewFiles.isEmpty()) {
            SynchState.CREATE_NEEDED
        } else if (path && openedFiles.find { it.getLocalPathString().equals(path) } ||
                !path && !openedFiles.isEmpty()) {
            SynchState.EXPORT_NEEDED
        } else if (change) {
            SynchState.CLEAN
        } else {
            SynchState.CREATE_NEEDED
        }
    }

    def scmStateForStatus(List<IFileSpec> reconcileStatus, List<IFileSpec> openedFiles,
                          IChangelistSummary change, String path) {
        IFileSpec pathSpec      = reconcileStatus.find { it.getLocalPathString() == path } ||
                openedFiles.find { it.getLocalPathString() == path }
        FileAction pathAction   = FileAction.ADD
        pathAction              = pathSpec?.getAction()
        // Find files which need resolving
        List<IExtendedFileSpec> filesNeedResolve = server.getExtendedFiles(openedFiles, -1, -1, -1,
                new FileStatOutputOptions().setOpenedNeedsResolvingFiles(true), null);

        if (!change) {
            new File(workingDir, path).exists() ? 'NEW' : 'NOT_FOUND'
        } else if (pathAction == FileAction.ADD) {
            'NEW'
        } else if (pathAction == FileAction.EDIT) {
            //changed== changes in index
            //modified == changes on disk
            'MODIFIED'
        } else if (pathAction == FileAction.DELETE) {
            'DELETED'
        } else if (reconcileStatus.find { it.getLocalPathString() == path && it.getAction() == FileAction.ADD }) {
            'UNTRACKED'
        } else if (filesNeedResolve.find { it.getLocalPathString() == path}) {
            'CONFLICT'
        } else {
            'NOT_FOUND'
        }
    }

    @Override
    JobState getJobStatus(final JobExportReference job) {
        getJobStatus(job, null)
    }

    @Override
    JobState getJobStatus(final JobExportReference job, final String originalPath) {
        log.debug("getJobStatus(${job.id},${originalPath})")
        if (!inited) {
            return null
        }
        def status = hasJobStatusCached(job, originalPath)
        if (!status) {
            status = refreshJobStatus(job, originalPath)
        }
        return createJobStatus(status, jobActionsForStatus(status))
    }

    List<Action> jobActionsForStatus(Map status) {
        if (status.synch != SynchState.CLEAN) {
            actionRefs(JOB_COMMIT_ACTION_ID)
        } else {
            []
        }
    }

    @Override
    String getRelativePathForJob(final JobReference job) {
        relativePath(job)
    }


    ScmDiffResult getFileDiff(final JobExportReference job) throws ScmPluginException {
        return getFileDiff(job, null)
    }

    ScmDiffResult getFileDiff(final JobExportReference job, final String originalPath) throws ScmPluginException {
        def file = getLocalFileForJob(job)
        def path = originalPath ?: relativePath(job)
        serialize(job, format)

        InputStream inputStream = p4Server.getFileContents(FileSpecBuilder.makeFileSpecList(path), false, true)
        def bytes = inputStream.getBytes()
        inputStream.close()

        if (bytes.length == 0) {
            return new P4DiffResult(oldNotFound: true)
        }

        def baos = new ByteArrayOutputStream()
        def diffs = diffContent(baos, bytes, file)

        def availableActions = diffs > 0 ? [actions[JOB_COMMIT_ACTION_ID]] : null
        return new P4DiffResult(content: baos.toString(),
                                 modified: diffs > 0,
                                 actions: availableActions
        )
    }
}
