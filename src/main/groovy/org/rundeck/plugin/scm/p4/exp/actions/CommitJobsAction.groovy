package org.rundeck.plugin.scm.p4.exp.actions

import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import com.perforce.p4java.core.IChangelist
import com.perforce.p4java.core.file.IFileSpec
import com.perforce.p4java.option.changelist.SubmitOptions
import com.perforce.p4java.option.client.ReconcileFilesOptions
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
                        description "Enter a commit message. Committing to branch: `" + plugin.branch + '`'
                        required true
                        renderingAsTextarea()
                    },

                    BuilderUtil.property {
                        string LabelAction.P_LABEL_NAME
                        title "Tag"
                        description "Enter a tag name to include, will be pushed with the branch."
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
        // determine action
        def internal = plugin.getStatusInternal(context, false)
        // Determine what files have been added, edited, or deleted
        List<IFileSpec> openedFiles = plugin.p4Client.reconcileFiles(new ArrayList<>(), new ReconcileFilesOptions())
        def result = new ScmExportResultImpl()

        if (openedFiles.isEmpty()) {
            // No opened files but some jobs were selected
            throw new ScmPluginException("No changes need to be exported - no opened files")
        }
        if (input[LabelAction.P_LABEL_NAME]) {
            LabelAction.validateLabelDoesNotExist(plugin, input[LabelAction.P_LABEL_NAME])
            LabelAction.validateLabelName(input[LabelAction.P_LABEL_NAME])
        }

        if (!jobs && !pathsToDelete) {
            throw new ScmPluginException("No jobs were selected")
        }
        if (!input[P_MESSAGE]) {
            throw new ScmPluginException("A ${P_MESSAGE} is required")
        }
        def commitIdentName = plugin.expand(plugin.committerName, context.userInfo)
        if (!commitIdentName) {
            ScmUserInfoMissing.fieldMissing("committerName")
        }
        def commitIdentEmail = plugin.expand(plugin.committerEmail, context.userInfo)
        if (!commitIdentEmail) {
            ScmUserInfoMissing.fieldMissing("committerEmail")
        }

        plugin.serializeAll(jobs, plugin.format)
        String commitMessage = input[P_MESSAGE].toString()

        IChangelist change = plugin.p4Server.getChangelist(IChangelist.DEFAULT)
        change.setDescription(commitMessage)
        List<String> p4Jobs = new ArrayList<>()
        if (input[p_P4_JOB]) {
            p4Jobs.add(input[p_P4_JOB])
        }
        change.submit(new SubmitOptions().setJobIds(p4Jobs))

        result.success = true
        result.commit = new P4ScmCommit(P4Util.metaForCommit(commit))

        if (result.success && input[LabelAction.P_LABEL_NAME]) {
            def tagResult = plugin.export(context, P4ExportPlugin.PROJECT_TAG_ACTION_ID, jobs, pathsToDelete, input)
            if (!tagResult.success) {
                return tagResult
            }
        }
        if (result.success && input[P_PUSH] == 'true') {
            return plugin.export(context, P4ExportPlugin.PROJECT_PUSH_ACTION_ID, jobs, pathsToDelete, input)
        }
        result.id = commit?.name


        result
    }

}
