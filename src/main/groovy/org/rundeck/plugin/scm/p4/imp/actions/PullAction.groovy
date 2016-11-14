package org.rundeck.plugin.scm.p4.imp.actions

import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.*
import org.rundeck.plugin.scm.p4.BaseAction
import org.rundeck.plugin.scm.p4.BuilderUtil
import org.rundeck.plugin.scm.p4.P4ImportAction
import org.rundeck.plugin.scm.p4.P4ImportPlugin

/**
 * Created by greg on 9/28/15.
 */
class PullAction extends BaseAction implements P4ImportAction {
    PullAction(final String id, final String title, final String description) {
        super(id, title, description)
    }

    @Override
    BasicInputView getInputView(final ScmOperationContext context, P4ImportPlugin plugin) {

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
                        select "refresh"
                        title "Synch Method"
                        description """Choose a method to synch the remote branch changes with local p4 repository.

* `merge` - merge remote changes into local changes
* `rebase` - rebase local changes on top of remote
"""
                        values "merge", "rebase"
                        defaultValue "merge"
                        required true
                    },
                    BuilderUtil.property {
                        select "resolution"
                        title "Conflict Resolution Strategy"
                        description """Choose a strategy to resolve conflicts in the synched files.

* `ours` - apply our changes over theirs
* `theirs` - apply their changes over ours"""
                        values(MergeStrategy.get()*.name)
                        defaultValue "ours"
                        required true
                    },
            ]
            )
        }
        BuilderUtil.inputViewBuilder(id) {
            title this.title
            description this.description
            if (status.branchTrackingStatus?.behindCount > 0) {
                buttonTitle("Pull Changes")
            } else {
                buttonTitle "Synch"
            }
            properties props
        }
    }

    @Override
    ScmExportResult performAction(
            final ScmOperationContext context,
            final P4ImportPlugin plugin,
            final JobImporter importer,
            final List<String> selectedPaths,
            final Map<String, String> input
    ) throws ScmPluginException
    {
        def status = plugin.getStatusInternal(context, false)


        if (status.branchTrackingStatus?.behindCount > 0 && status.branchTrackingStatus?.aheadCount > 0) {
            plugin.gitResolve(context, input.refresh == 'rebase', input.resolution)
        } else if (status.branchTrackingStatus?.behindCount > 0) {
            gitPull(context, plugin)
        } else {
            //no action
        }

    }

    ScmExportResult gitPull(final ScmOperationContext context, final P4ImportPlugin plugin) {
        def pullResult = plugin.gitPull(context)
        def result = new ScmExportResultImpl()
        result.success = pullResult.successful
        result.message = "Git Pull "+(result.success?'succeeded':'failed')
        result.extendedMessage = pullResult.mergeResult?.toString()?:null
        result
    }

}
