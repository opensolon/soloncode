package org.noear.soloncode.sdk;

import org.noear.soloncode.sdk.exceptions.SolonCodeSDKException;
import org.noear.soloncode.sdk.parsing.ParsedMessage;
import org.noear.soloncode.sdk.permission.ToolPermissionCallback;
import org.noear.soloncode.sdk.transport.CLIOptions;
import org.noear.soloncode.sdk.types.Message;

import java.util.Iterator;
import java.util.Map;

/**
 * Internal session contract used by the unified request-oriented client.
 *
 * <p>The public client depends on this package-private abstraction so transport and
 * lifecycle details do not leak into the SDK API.</p>
 */
interface SolonCodeSession extends AutoCloseable {
    void connect(String initialPrompt) throws SolonCodeSDKException;
    void query(String prompt) throws SolonCodeSDKException;
    Iterator<ParsedMessage> receiveResponse();
    void interrupt() throws SolonCodeSDKException;
    void setModel(String model) throws SolonCodeSDKException;
    void setPermissionMode(String mode) throws SolonCodeSDKException;
    CLIOptions getOptions();
    String getCurrentModel();
    String getCurrentPermissionMode();
    Map<String, Object> getServerInfo();
    boolean isConnected();
    void setToolPermissionCallback(ToolPermissionCallback callback);
    ToolPermissionCallback getToolPermissionCallback();
    @Override
    void close();
}
