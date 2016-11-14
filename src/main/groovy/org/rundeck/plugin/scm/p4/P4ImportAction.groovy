package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.views.Action
import com.dtolabs.rundeck.core.plugins.views.BasicInputView
import com.dtolabs.rundeck.plugins.scm.JobImporter
import com.dtolabs.rundeck.plugins.scm.ScmExportResult
import com.dtolabs.rundeck.plugins.scm.ScmOperationContext

/**
 * Created by greg on 9/10/15.
 */
interface P4ImportAction extends Action {
    BasicInputView getInputView(final ScmOperationContext context, final P4ImportPlugin plugin)

    ScmExportResult performAction(
            final ScmOperationContext context,
            final P4ImportPlugin plugin,
            final JobImporter importer,
            final List<String> selectedPaths,
            final Map<String, String> input
    )

}
