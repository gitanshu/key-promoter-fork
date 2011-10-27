package org.jetbrains.contest.keypromoter;

import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.actionSystem.impl.ActionButton;
import com.intellij.openapi.actionSystem.impl.ActionMenuItem;
import com.intellij.openapi.actionSystem.impl.actionholder.ActionRef;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ApplicationComponent;
import com.intellij.openapi.keymap.impl.ui.EditKeymapsDialog;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.wm.impl.IdeFrameImpl;
import com.intellij.openapi.wm.impl.StripeButton;
import com.intellij.util.Alarm;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Date: 04.09.2006
 * Time: 14:11:03
 */
public class KeyPromoter implements ApplicationComponent, AWTEventListener {

    // Fields with actions of supported classes
    private Map<Class, Field> myClassFields = new HashMap<Class, Field>(5);

    // DataContext field to get frame on Mac for example
    private Field myMenuItemDataContextField;
    private KeyPromoterSettings mySettings;

    // Alarm object to perform animation effects
    private Alarm myAlarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD);

    // Presentation and stats fields.
    private JWindow myTipWindow;
    private Map<String, Integer> stats = Collections.synchronizedMap(new HashMap<String, Integer>());
    private Map<String, Integer> withoutShortcutStats = Collections.synchronizedMap(new HashMap<String, Integer>());

    public void initComponent() {
        Toolkit.getDefaultToolkit().addAWTEventListener(this, AWTEvent.MOUSE_EVENT_MASK | AWTEvent.WINDOW_EVENT_MASK | AWTEvent.WINDOW_STATE_EVENT_MASK/* | AWTEvent.KEY_EVENT_MASK*/);
        KeyPromoterConfiguration component = ApplicationManager.getApplication().getComponent(KeyPromoterConfiguration.class);
        mySettings = component.getSettings();

        // DataContext field to get frame on Mac for example
        myMenuItemDataContextField = KeyPromoterUtils.getFieldOfType(ActionMenuItem.class, DataContext.class);
    }

    public void disposeComponent() {
        Toolkit.getDefaultToolkit().removeAWTEventListener(this);
    }

    @NotNull
    public String getComponentName() {
        return "KeyPromoter";
    }

    // AWT magic
    public void eventDispatched(AWTEvent e) {
        if (e.getID() == MouseEvent.MOUSE_RELEASED && ((MouseEvent) e).getButton() == MouseEvent.BUTTON1) {
            handleMouseEvent(e);

        } else if (e.getID() == WindowEvent.WINDOW_ACTIVATED | e.getID() == Event.WINDOW_MOVED) {
            handleWindowEvent(e);
        }
    }

    private void handleWindowEvent(AWTEvent e) {
        // To paint tip over dialogs
        if (myTipWindow != null && myTipWindow.isVisible()) {
            myTipWindow.setVisible(false);
            myTipWindow.setVisible(true);
            myTipWindow.repaint();
        }
    }

    private void handleMouseEvent(AWTEvent e) {
        final Object source = e.getSource();
        String shortcutText = "";
        String description = "";

        AnAction anAction = null;


        if (mySettings.isToolWindowButtonsEnabled() && source instanceof StripeButton) {
            // This is hack!!!
            char mnemonic = ((char) ((StripeButton) source).getMnemonic2());
            if (mnemonic >= '0' && mnemonic <= '9') {
                shortcutText = "Alt+" + mnemonic;
                description = ((StripeButton) source).getText();
            }
        } else if (mySettings.isAllButtonsEnabled() && source instanceof JButton) {
            char mnemonic = ((char) ((JButton) source).getMnemonic());
            if (mnemonic > 0) {
                // Not respecting Mac Meta key yet
                shortcutText = "Alt+" + mnemonic;
                description = ((JButton) source).getText();
            }
        } else {
            Field field;
            if (!myClassFields.containsKey(source.getClass())) {
                field = KeyPromoterUtils.getFieldOfType(source.getClass(), AnAction.class);
                if (field == null) {
                    field = KeyPromoterUtils.getFieldOfType(source.getClass(), ActionRef.class);
                }
                myClassFields.put(source.getClass(), field);
            } else {
                field = myClassFields.get(source.getClass());
            }
            try {
                if ((mySettings.isMenusEnabled() && source instanceof ActionMenuItem) ||
                        (mySettings.isToolbarButtonsEnabled() && source instanceof ActionButton)) {
                    Object actionItem = field.get(source);
                    if (actionItem instanceof AnAction) {
                        anAction = (AnAction) actionItem;
                    } else if (actionItem instanceof ActionRef) {
                        anAction = ((ActionRef) actionItem).getAction();
                    }
                }
            } catch (IllegalAccessException e1) {
                // it is bad but ...
            }
            if (anAction != null && anAction.getShortcutSet() != null) {
                shortcutText = KeyPromoterUtils.getKeyboardShortcutsText(anAction);
                description = anAction.getTemplatePresentation().getText();
            }
        }

        handle(e, shortcutText, description, anAction);
    }

    private void handle(final AWTEvent e, String shortcutText, String description, AnAction anAction) {
        Object source = e.getSource();
        // Get current frame, not sure that it respects API
        JFrame frame = getFrame(source);
        if (frame == null) {
            return;
        }
        if (!StringUtil.isEmpty(shortcutText)) {
            if (stats.get(shortcutText) == null) {
                stats.put(shortcutText, 0);
            }
            stats.put(shortcutText, stats.get(shortcutText) + 1);

            // Write shortcut to the brain card

            showTip(frame, e, KeyPromoterUtils.renderMessage(description, shortcutText, stats.get(shortcutText)));
        } else {
            // Suggest to assign shortcut ot action without shortcut or record such action invocation
            if (anAction != null) {
                String id = ActionManager.getInstance().getId(anAction);
                if (id != null) {
                    if (withoutShortcutStats.get(id) == null) {
                        withoutShortcutStats.put(id, 0);
                    }
                    withoutShortcutStats.put(id, withoutShortcutStats.get(id) + 1);
                    if (mySettings.getProposeToCreateShortcutCount() > 0 && withoutShortcutStats.get(id) % mySettings.getProposeToCreateShortcutCount() == 0) {
                        String actionLabel = anAction.getTemplatePresentation().getDescription();
                        if (StringUtil.isEmpty(actionLabel)) {
                            actionLabel = anAction.getTemplatePresentation().getText();
                        }
                        if (Messages.showYesNoDialog(frame, "Would you like to assign shortcut to '" + actionLabel + "' action cause we noticed it was used " + withoutShortcutStats.get(id) + " time(s) by mouse?",
                                "[KeyPromoter said]: Keyboard usage more productive!", Messages.getQuestionIcon()) == 0) {
                            EditKeymapsDialog dialog = new EditKeymapsDialog(((IdeFrameImpl) frame).getProject(), id);
                            dialog.show();
                        }
                    }
                }
            }
        }
    }

    private void showTip(JFrame frame, AWTEvent e, String text) {

        // Interrupt any pending requests
        myAlarm.cancelAllRequests();

        // Cleanup tip if it is not first run
        if (myTipWindow != null) {
            myTipWindow.dispose();
            myTipWindow = null;
        }
        myTipWindow = new TipWindow(frame, text, (Component) e.getSource());
        myTipWindow.setVisible(true);

        final int[] stepsCount = new int[]{1};
        int stepDuration = (int) mySettings.getDisplayTime();
        if (mySettings.getFlashAnimationDelay() != 0) {
            stepsCount[0] = (int) (mySettings.getDisplayTime() / mySettings.getFlashAnimationDelay());
            // Alpha transparency decreased on each redraw by cycle
            stepDuration = (int) mySettings.getFlashAnimationDelay();
        }

        // Repainting with specified delay and steps count
        final int stepDuration1 = stepDuration;
        myAlarm.addRequest(new Runnable() {

            public void run() {
                myTipWindow.repaint();
                if (stepsCount[0]-- > 0) {
                    myAlarm.addRequest(this, stepDuration1);
                } else {
                    myTipWindow.dispose();
                    myTipWindow = null;
                }

            }
        }, stepDuration);
    }

    private JFrame getFrame(Object source) {
        JFrame frame = (JFrame) SwingUtilities.getAncestorOfClass(JFrame.class, (Component) source);
        if (frame == null) {
            // On Mac menus detached from main frame :(
            if (source instanceof JMenuItem) {
                try {
                    DataContext dataContext = (DataContext) myMenuItemDataContextField.get(source);
                    if (dataContext != null) {
                        Component component = (Component) dataContext.getData(PlatformDataKeys.CONTEXT_COMPONENT.getName());
                        frame = (JFrame) SwingUtilities.getAncestorOfClass(JFrame.class, component);
                    }
                } catch (Exception e1) {
                    // it is bad but ...
                }
            }
        }
        return frame;
    }

}
