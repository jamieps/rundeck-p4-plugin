package org.rundeck.plugin.scm.p4.exp.actions

import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.client.IClient
import com.perforce.p4java.core.ChangelistStatus
import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.IUser
import com.perforce.p4java.core.file.FileSpecBuilder
import com.perforce.p4java.core.file.FileSpecOpStatus
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.impl.generic.core.Changelist
import com.perforce.p4java.impl.mapbased.server.Server
import com.perforce.p4java.option.changelist.SubmitOptions
import com.perforce.p4java.option.client.ReconcileFilesOptions
import com.perforce.p4java.option.client.RevertFilesOptions
import com.perforce.p4java.server.IOptionsServer
import com.perforce.p4java.server.ServerFactory
import org.rundeck.plugin.scm.p4.*

/**
 * Submit changes to the job configuration to Perforce.
 */
class CommitJobsAction extends BaseAction implements P4ExportAction {

    public static final String P_MESSAGE = 'message'
    public static final String P_PUSH = 'push'
    public static final String P_P4_JOB = 'job'

    CommitJobsAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    BasicInputView getInputView(final ScmOperationContext context, P4ExportPlugin plugin) {
        BuilderUtil.inputViewBuilder(id) {
            title getTitle()
            description getDescription()
            buttonTitle "Commit"
            properties([
                    BuilderUtil.property {
                        string P_MESSAGE
                        title "Commit Message"
                        description "Enter a commit message."
                        required true
                        renderingAsTextarea()
                    },

                    BuilderUtil.property {
                        string LabelAction.P_LABEL_NAME
                        title "Label"
                        description "Enter a label name to use."
                        required false
                    },

                    BuilderUtil.property {
                        string P_P4_JOB
                        title "Perforce Job"
                        description "Enter a job to submit changes under"
                        required false
                    },
            ]
            )
        }
    }

    @Override
    ScmExportResult perform(
            final P4ExportPlugin plugin,
            final Set<JobExportReference> jobs,
            final Set<String> pathsToDelete,
            final ScmOperationContext context,
            final Map<String, String> input
    ) throws ScmPluginException
    {
        def commitIdentName = plugin.expand(plugin.committerName, context.userInfo)
        if (!commitIdentName) {
            plugin.logger.debug("CommitJobsAction: no committer name")
            ScmUserInfoMissing.fieldMissing("committerName")
        }
        def commitIdentEmail = plugin.expand(plugin.committerEmail, context.userInfo)
        if (!commitIdentEmail) {
            plugin.logger.debug("CommitJobsAction: no committer email")
            ScmUserInfoMissing.fieldMissing("committerEmail")
        }
        String commitMessage = input[P_MESSAGE].toString()

        // Impersonate the actual user we want to commit as
        // TODO: We have to create a new IOptionsServer instance, this is inefficient.
        // If we do not create a new instance of IOptionsServer and re-use the Server instance from the plugin
        // then we will change the current Perforce user for all commands which may be running concurrently.
        IOptionsServer srv = ServerFactory.getOptionsServer(plugin.config.uri, null)
        srv.connect()
        IUser committerUser = srv.getUser(commitIdentName)
        if (committerUser == null) {
            srv.disconnect()
            throw new ScmPluginException("Committer username, ${commitIdentName}, is not valid")
        }
        srv.login(committerUser, null, null)
        srv.setUserName(commitIdentName)

        IClient srvClient = srv.getClient(plugin.p4Client.getName())

        Changelist changeListImpl = new Changelist(IChangelist.UNKNOWN,
                plugin.p4Client.getName(), commitIdentName,
                ChangelistStatus.NEW, new Date(), commitMessage, false, (Server)srv)
        IChangelist change = srvClient.createChangelist(changeListImpl)

        jobs.each { job -> plugin.logger.debug("CommitJobsAction job id: ${job.id} name: ${job.jobName} " +
                "groupPath: ${job.groupPath} jobAndGroup: ${job.jobAndGroup}") }

        // Determine what files have been added, edited, or deleted

        // First clean workspace
        srvClient.revertFiles([], new RevertFilesOptions())

        // Build a list of specs to reconcile
        List<IFileSpec> fileSpecs   = FileSpecBuilder.makeFileSpecList(jobs.collect({
            "//${srvClient.getName()}/${plugin.relativePath(it)}".toString()
        }))
        List<IFileSpec> openedFiles = srvClient.reconcileFiles(fileSpecs,
                new ReconcileFilesOptions(changelistId: change.getId()))

        plugin.logger.debug("CommitJobsAction: ${openedFiles.size()} opened file(s) (${fileSpecs} -> ${openedFiles}")
        openedFiles?.each { fileSpec ->
            if (fileSpec?.getOpStatus() == FileSpecOpStatus.VALID) {
                plugin.logger.debug("open: ${fileSpec.getDepotPath()}")
            } else {
                plugin.logger.error(fileSpec?.getStatusMessage())
            }
        }

        change.update()

        def result = new ScmExportResultImpl()

        if (openedFiles.isEmpty()) {
            // No opened files but some jobs were selected
            plugin.logger.debug("CommitJobsAction: no opened files")
            throw new ScmPluginException("No changes need to be exported - no opened files")
        }
        if (input[LabelAction.P_LABEL_NAME]) {
            plugin.logger.debug("CommitJobsAction: label name specified")
            LabelAction.validateLabelDoesNotExist(plugin, input[LabelAction.P_LABEL_NAME])
            LabelAction.validateLabelName(input[LabelAction.P_LABEL_NAME])
        }

        if (!jobs && !pathsToDelete) {
            plugin.logger.debug("CommitJobsAction: no jobs selected!")
            throw new ScmPluginException("No jobs were selected")
        }
        if (!input[P_MESSAGE]) {
            plugin.logger.debug("CommitJobsAction: no message specified!")
            throw new ScmPluginException("A ${P_MESSAGE} is required")
        }

        plugin.serializeAll(jobs, plugin.format)
        plugin.logger.debug("CommitJobsAction: committer: ${commitIdentName} ${commitIdentEmail} commit message: ${commitMessage}")

        List<String> p4Jobs = new ArrayList<>()
        if (input[P_P4_JOB]) {
            p4Jobs.add(input[P_P4_JOB])
        }
        result.success = true
        List<IFileSpec> submittedFiles = change.submit(new SubmitOptions().setJobIds(p4Jobs))
        submittedFiles?.each { fileSpec ->
            FileSpecOpStatus status = fileSpec?.getOpStatus()
            if (status == FileSpecOpStatus.VALID) {
                plugin.logger.debug("submitted: ${fileSpec.getDepotPath()}")
            } else {
                if (status in [FileSpecOpStatus.ERROR, FileSpecOpStatus.CLIENT_ERROR, FileSpecOpStatus.UNKNOWN]) {
                    plugin.logger.error(fileSpec?.getStatusMessage())
                    result.success = false
                    result.message = fileSpec?.getStatusMessage()
                } else {
                    plugin.logger.debug(fileSpec?.getStatusMessage())
                }
            }
        }

        if (!result.success) {
            srvClient.revertFiles(fileSpecs, new RevertFilesOptions())
        }

        // Change submitted, we can switch back to the global Perforce server instance.
        srv.logout()
        srv.disconnect()

        result.commit = new P4ScmCommit(P4Util.metaForCommit(change, plugin.p4Server))

        if (result.success && input[LabelAction.P_LABEL_NAME]) {
            def tagResult = plugin.export(context, P4ExportPlugin.PROJECT_TAG_ACTION_ID, jobs, pathsToDelete, input)
            if (!tagResult.success) {
                return tagResult
            }
        }
        if (result.success && input[P_PUSH] == 'true') {
            return plugin.export(context, P4ExportPlugin.PROJECT_PUSH_ACTION_ID, jobs, pathsToDelete, input)
        }
        result.setId(String.valueOf(change?.getId()))

        result
    }

}
