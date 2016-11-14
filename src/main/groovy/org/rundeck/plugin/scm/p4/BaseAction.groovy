package org.rundeck.plugin.scm.p4

import com.dtolabs.rundeck.core.plugins.views.Action

/**
 * Represents a basic action.
 */
abstract class BaseAction implements Action {
    String id
    String title
    String description
    String iconName

    BaseAction(String id, String title, String description, String iconName) {
        this.id = id
        this.title = title
        this.description = description
        this.iconName = iconName
    }

    BaseAction(String id, String title, String description) {
        this(id, title, description, null);
    }

}
