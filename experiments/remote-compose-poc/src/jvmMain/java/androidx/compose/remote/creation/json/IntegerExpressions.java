/*
 * Feasibility adapter for the AndroidX authoring-JSON integer-expression gap. This is not a
 * production dialect or a published library fork. It lives beside the parser because alpha19's
 * registration API exposes ExpressionParser and variable tables with package visibility.
 */
package androidx.compose.remote.creation.json;

import androidx.compose.remote.core.operations.Utils;

public final class IntegerExpressions {
  private IntegerExpressions() {}

  public static void install(RemoteComposeJsonParser parser) {
    parser.registerComponentParser("integerExpression", (component, modifier, writer, current) -> {
      String name = component.getString("name");
      if (current.mIntegerVariables.containsKey(name) || current.mVariables.containsKey(name)) {
        throw new org.json.JSONException("Duplicate expression name: " + name);
      }
      long encoded = current.getExpressionParser().parseIntegerExpression(component.getString("value"));
      int id = (int) encoded;
      current.mIntegerVariables.put(name, encoded);
      current.mVariables.put(name, Utils.asNan(id));
      current.recordVariable(name, id);
    });
  }
}
