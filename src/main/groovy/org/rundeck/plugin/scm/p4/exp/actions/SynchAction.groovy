package org.rundeck.plugin.scm.p4.exp.actions

import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import org.rundeck.plugin.scm.p4.BaseAction
import org.rundeck.plugin.scm.p4.BuilderUtil
import org.rundeck.plugin.scm.p4.P4ExportAction
import org.rundeck.plugin.scm.p4.P4ExportPlugin

/**
 * Created by greg on 9/8/15.
 */
class SynchAction extends BaseAction implements P4ExportAction {
    private static final enum MergeOptions {
        ACCEPT_YOURS("yours"),
        ACCEPT_THEIRS("theirs")
    }

    SynchAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    @Override
    BasicInputView getInputView(final ScmOperationContext context, P4ExportPlugin plugin) {
        def status = plugin.getStatusInternal(context, false)
        def props = [
                BuilderUtil.property {
                    string "status"
                    title "Git Status"
                    renderingOption StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.STATIC_TEXT
                    renderingOption StringRenderingConstants.STATIC_TEXT_CONTENT_TYPE_KEY, "text/x-markdown"
                    defaultValue status.message + """

Pulling from remote branch: `${plugin.branch}`"""
                },
        ]
        if (status.branchTrackingStatus?.behindCount > 0 && status.branchTrackingStatus?.aheadCount > 0) {
            props.addAll([
                    BuilderUtil.property {
                        select "resolution"
                        title "Conflict Resolution Strategy"
                        description """Choose a strategy to resolve conflicts in the synched files.

* `ours` - apply our changes over theirs
* `theirs` - apply their changes over ours
* `recursive` - recursive merge"""
                        values([MergeStrategy.OURS,
                                MergeStrategy.THEIRS,
                                MergeStrategy.RECURSIVE]*.name)
                        defaultValue "ours"
                        required true
                    },
            ]
            )
        }
        BuilderUtil.inputViewBuilder(id) {
            title this.title
            description this.description
            buttonTitle "Synch"
            properties props
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
        def status = plugin.getStatusInternal(context, false)


        // TODO: Do a p4 resolve -am or -as here, if there are conflicts
        if (status.branchTrackingStatus?.behindCount > 0 && status.branchTrackingStatus?.aheadCount > 0) {
            plugin.p4Resolve(context, input.resolution)
        } else if (status.branchTrackingStatus?.behindCount > 0) {
            gitPull(context, plugin)
        } else {
            //no action
        }

    }

    ScmExportResult gitPull(final ScmOperationContext context, final P4ExportPlugin plugin) {
        def pullResult = plugin.gitPull(context)
        def result = new ScmExportResultImpl()
        result.success = pullResult.successful
        result.message = "Git Pull " + (result.success ? 'succeeded' : 'failed')
        result.extendedMessage = pullResult.mergeResult?.toString() ?: null
        result
    }
}
