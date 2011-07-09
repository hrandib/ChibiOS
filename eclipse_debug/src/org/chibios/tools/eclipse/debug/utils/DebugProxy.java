package org.chibios.tools.eclipse.debug.utils;

import java.util.HashMap;
import java.util.LinkedHashMap;

import org.eclipse.cdt.debug.core.cdi.model.ICDITarget;
import org.eclipse.cdt.debug.internal.core.model.CDebugTarget;
import org.eclipse.cdt.debug.mi.core.MIException;
import org.eclipse.cdt.debug.mi.core.MIFormat;
import org.eclipse.cdt.debug.mi.core.MISession;
import org.eclipse.cdt.debug.mi.core.cdi.model.Target;
import org.eclipse.cdt.debug.mi.core.command.CommandFactory;
import org.eclipse.cdt.debug.mi.core.command.MIDataEvaluateExpression;
import org.eclipse.cdt.debug.mi.core.command.MIDataReadMemory;
import org.eclipse.cdt.debug.mi.core.output.MIDataEvaluateExpressionInfo;
import org.eclipse.cdt.debug.mi.core.output.MIDataReadMemoryInfo;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.model.IDebugTarget;

@SuppressWarnings("restriction")
public class DebugProxy {

  private CommandFactory cmd_factory;
  private MISession mi_session;

  protected final static String[] threadStates = {
    "READY",
    "CURRENT",
    "SUSPENDED",
    "WTSEM",
    "WTMTX",
    "WTCOND",
    "SLEEPING",
    "WTEXIT",
    "WTOREVT",
    "WTANDEVT",
    "SNDMSGQ",
    "SNDMSG",
    "WTMSG",
    "WTQUEUE",
    "FINAL"
  };

  private void getSession(CDebugTarget target)
      throws DebugProxyException {
    ICDITarget[] targets = target.getCDISession().getTargets();
    ICDITarget cdi_target = null;
    for (int i = 0; i < targets.length; i++) {
      if (targets[i] instanceof Target) {
        cdi_target = targets[i];
        break;
      }
    }
    if (cdi_target == null)
      throw new DebugProxyException("no CDI session found");
    mi_session = ((Target)cdi_target).getMISession();
    cmd_factory = mi_session.getCommandFactory();
  }

  public DebugProxy()
      throws DebugProxyException {
    IDebugTarget[] targets = DebugPlugin.getDefault().getLaunchManager().getDebugTargets();
    for (IDebugTarget target:targets) {
      if(target instanceof CDebugTarget) {
        getSession((CDebugTarget)target);
        return;
      }
    }
  }

  public DebugProxy(CDebugTarget target)
      throws DebugProxyException {
    getSession(target);
  }

  public String evaluateExpression(String expression)
      throws DebugProxyException {
    if (mi_session.getMIInferior().isRunning())
      return null;
    MIDataEvaluateExpression expr = cmd_factory.createMIDataEvaluateExpression(expression);
    try {
      mi_session.postCommand(expr);
      MIDataEvaluateExpressionInfo info = expr.getMIDataEvaluateExpressionInfo();
      if (info != null)
        return info.getExpression();
    } catch (MIException e) {}
    throw new DebugProxyException("error evaluating the expression: '" +
                                  expression + "'");
  }

  public String readCString(long address, int max)
      throws DebugProxyException {
    if (mi_session.getMIInferior().isRunning())
      return null;
    MIDataReadMemory mem = cmd_factory.createMIDataReadMemory(0,
                                                              Long.toString(address),
                                                              MIFormat.HEXADECIMAL,
                                                              1,
                                                              1,
                                                              max,
                                                              '.');
    try {
      mi_session.postCommand(mem);
       MIDataReadMemoryInfo info = mem.getMIDataReadMemoryInfo();
       if (info != null) {
          String s = info.getMemories()[0].getAscii();
          int i = s.indexOf('.');
          if (i >= 0)
            return s.substring(0, s.indexOf('.'));
          else
            return s;
       }
    } catch (MIException e) {}
    throw new DebugProxyException("error reading memory at " +
        address);
  }

  /**
   * @brief   Return the list of threads.
   * @details The threads list is fetched from memory by scanning the
   *          registry.
   *
   * @return  A @p LinkedHashMap object whose keys are the threads addresses
   *          as decimal strings, the value is an @p HashMap of the thread
   *          fields:
   *          - stack
   *          - name
   *          - state
   *          - state_s
   *          - flags
   *          - prio
   *          - refs
   *          - time
   *          - u
   *          .
   *          Missing fields are set to "-".
   * @retval null                   If the debugger encountered an error or
   *                                the target is running.
   *
   * @throws DebugProxyException    If the debugger is active but the registry
   *                                is not found, not initialized or corrupted.
   */
  public LinkedHashMap<String, HashMap<String, String>> readThreads()
      throws DebugProxyException {
    // rlist structure address.
    String rlist;
    try {
      rlist = evaluateExpression("(uint32_t)&rlist");
      if (rlist == null)
        return null;
    } catch (DebugProxyException e) {
      throw new DebugProxyException("ChibiOS/RT not found on target");
    } catch (Exception e) {
      return null;
    }

    // Scanning registry.
    LinkedHashMap<String, HashMap<String, String>> lhm =
        new LinkedHashMap<String, HashMap<String, String>>(10);
    String current = rlist;
    String previous = rlist;
    while (true) {
      
      // Fetching next thread in the registry (newer link). This fetch fails
      // if the register is not enabled in the kernel and the p_newer field
      // does not exist.
      try {
        current = evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_newer");
      } catch (DebugProxyException e1) {
        throw new DebugProxyException("ChibiOS/RT registry not enabled in kernel");
      }

      // This can happen if the kernel is not initialized yet or if the
      // registry is corrupted.
      if (current.compareTo("0") == 0)
        throw new DebugProxyException("ChibiOS/RT registry integrity check failed, NULL pointer");

      // TODO: integrity check on the pointer value (alignment, range).

      // The previous thread in the list is fetched as a integrity check.
      String older = evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_older");
      if (older.compareTo("0") == 0)
        throw new DebugProxyException("ChibiOS/RT registry integrity check failed, NULL pointer");
      if (previous.compareTo(older) != 0)
        throw new DebugProxyException("ChibiOS/RT registry integrity check failed, double linked list violation");

      // End of the linked list condition.
      if (current.compareTo(rlist) == 0)
        break;

      // Hash of threads fields.
      HashMap<String, String> map = new HashMap<String, String>(16);

      // Fetch of the various fields in the Thread structure. Some fields
      // are optional so are placed within try-catch.
      int n;
      try {
        n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_ctx.r13"));
        map.put("stack", Integer.toString(n));
      } catch (DebugProxyException e) {
        try {
          n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_ctx.sp"));
          map.put("stack", Integer.toString(n));
        } catch (DebugProxyException ex) {
          map.put("stack", "-");
        }
      }

      try {
        n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_name"));
        if (n == 0)
          map.put("name", "<no name>");
        else
          map.put("name", readCString(n, 16));
      } catch (DebugProxyException e) {
        map.put("name", "-");
      }

      n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_state"));
      map.put("state", Integer.toString(n));
      if ((n >= 0) && (n < threadStates.length)) {
        map.put("state_s", threadStates[n]);
      }
      else
        map.put("state_s", "unknown");

      n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_flags"));
      map.put("flags", Integer.toString(n));

      n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_prio"));
      map.put("prio", Integer.toString(n));

      try {
        n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_refs"));
        map.put("refs", Integer.toString(n));
      } catch (DebugProxyException e) {
        map.put("refs", "-");
      }

      try {
        n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_time"));
        map.put("time", Integer.toString(n));
      } catch (DebugProxyException e) {
        map.put("time", "-");
      }

      try {
        n = HexUtils.parseInt(evaluateExpression("(uint32_t)((Thread *)" + current + ")->p_u.wtobjp"));
        map.put("u", Integer.toString(n));
      } catch (DebugProxyException e) {
        map.put("u", "-");
      }

      // Inserting the new thread map into the threads list.
      lhm.put(current, map);

      previous = current;
    }
    return lhm;
  }

  /**
   * @brief   Return the list of timers.
   * @details The timers list is fetched from memory by scanning the
   *          @p vtlist structure.
   *
   * @return  A @p LinkedHashMap object whose keys are the timers addresses
   *          as decimal strings, the value is an @p HashMap of the timers
   *          fields:
   *          - delta
   *          - func
   *          - par
   *          .
   * @retval null                   If the debugger encountered an error or
   *                                the target is running.
   *
   * @throws DebugProxyException    If the debugger is active but the structure
   *                                @p vtlist is not found, not initialized or
   *                                corrupted.
   */
  public LinkedHashMap<String, HashMap<String, String>> readTimers()
      throws DebugProxyException {
    // Delta list structure address.
    String vtlist;
    try {
      vtlist = evaluateExpression("(uint32_t)&vtlist");
      if (vtlist == null)
        return null;
    } catch (DebugProxyException e) {
      throw new DebugProxyException("ChibiOS/RT not found on target");
    } catch (Exception e) {
      return null;
    }

    // Scanning delta list.
    LinkedHashMap<String, HashMap<String, String>> lhm =
        new LinkedHashMap<String, HashMap<String, String>>(10);
    String current = vtlist;
    String previous = vtlist;
    while (true) {
      
      // Fetching next timer in the delta list (vt_next link).
      current = evaluateExpression("(uint32_t)((VirtualTimer *)" + current + ")->vt_next");

      // This can happen if the kernel is not initialized yet or if the
      // delta list is corrupted.
      if (current.compareTo("0") == 0)
        throw new DebugProxyException("ChibiOS/RT delta list integrity check failed, NULL pointer");

      // TODO: integrity check on the pointer value (alignment, range).

      // The previous timer in the delta list is fetched as a integrity check.
      String prev = evaluateExpression("(uint32_t)((VirtualTimer *)" + current + ")->vt_prev");
      if (prev.compareTo("0") == 0)
        throw new DebugProxyException("ChibiOS/RT delta list integrity check failed, NULL pointer");
      if (previous.compareTo(prev) != 0)
        throw new DebugProxyException("ChibiOS/RT delta list integrity check failed, double linked list violation");

      // End of the linked list condition.
      if (current.compareTo(vtlist) == 0)
        break;

      // Hash of timers fields.
      HashMap<String, String> map = new HashMap<String, String>(16);

      // Fetch of the various fields in the Thread structure. Some fields
      // are optional so are placed within try-catch.
      int n = HexUtils.parseInt(evaluateExpression("(uint32_t)((VirtualTimer *)" + current + ")->vt_time"));
      map.put("delta", Integer.toString(n));

      n = HexUtils.parseInt(evaluateExpression("(uint32_t)((VirtualTimer *)" + current + ")->vt_func"));
      map.put("func", Integer.toString(n));

      n = HexUtils.parseInt(evaluateExpression("(uint32_t)((VirtualTimer *)" + current + ")->vt_par"));
      map.put("par", Integer.toString(n));

      // Inserting the new thread map into the threads list.
      lhm.put(current, map);

      previous = current;
    }
    return lhm;
  }

  /**
   * @brief   Return the list of trace buffer entries.
   * @details The trace buffer is fetched from memory by scanning the
   *          @p ch_dbg_trace_buffer array.
   *
   * @return  A @p LinkedHashMap object whose keys are the timers addresses
   *          as decimal strings, the value is an @p HashMap of the timers
   *          fields:
   *          - time
   *          - tp
   *          - wtobjp
   *          - state
   *          - state_s
   *          .
   * @retval null                   If the debugger encountered an error or
   *                                the target is running.
   *
   * @throws DebugProxyException    If the debugger is active but the structure
   *                                @p ch_dbg_trace_buffer is not found, not
   *                                initialized or corrupted.
   */
  public LinkedHashMap<String, HashMap<String, String>> readTraceBuffer()
      throws DebugProxyException {
    
    // Trace buffer size.
    String s;
    try {
      s = evaluateExpression("(uint32_t)ch_dbg_trace_buffer.tb_size");
      if (s == null)
        return null;
    } catch (DebugProxyException e) {
      throw new DebugProxyException("trace buffer not found on target");
    } catch (Exception e) {
      return null;
    }

    int tbsize = HexUtils.parseInt(s);
    int tbrecsize = HexUtils.parseInt(evaluateExpression("(uint32_t)sizeof (ch_swc_event_t)"));
    int tbstart = HexUtils.parseInt(evaluateExpression("(uint32_t)ch_dbg_trace_buffer.tb_buffer"));
    int tbend = HexUtils.parseInt(evaluateExpression("(uint32_t)&ch_dbg_trace_buffer.tb_buffer[" + tbsize + "]"));
    int tbptr = HexUtils.parseInt(evaluateExpression("(uint32_t)ch_dbg_trace_buffer.tb_ptr"));

    // Scanning the trace buffer from the oldest event to the newest.
    LinkedHashMap<String, HashMap<String, String>> lhm =
        new LinkedHashMap<String, HashMap<String, String>>(10);
    int n = tbsize;
    int i = -tbsize + 1;
    while (n >= 0) {
      // Hash of timers fields.
      HashMap<String, String> map = new HashMap<String, String>(16);

      String time = evaluateExpression("(uint32_t)(((ch_swc_event_t *)" + tbptr + ")->se_time)");
      map.put("time", time);

      String tp = evaluateExpression("(uint32_t)(((ch_swc_event_t *)" + tbptr + ")->se_tp)");
      map.put("tp", tp);

      String wtobjp = evaluateExpression("(uint32_t)(((ch_swc_event_t *)" + tbptr + ")->se_wtobjp)");
      map.put("wtobjp", wtobjp);

      int state = HexUtils.parseInt(evaluateExpression("(uint32_t)(((ch_swc_event_t *)" + tbptr + ")->se_state)"));
      map.put("state", Integer.toString(state));
      if ((state >= 0) && (state < threadStates.length))
        map.put("state_s", threadStates[state]);
      else
        map.put("state_s", "unknown");

      // Inserting the new event map into the events list.
      if (tp.compareTo("0") != 0)
        lhm.put(Integer.toString(i), map);

      tbptr += tbrecsize;
      if (tbptr >= tbend)
        tbptr = tbstart;
      n--;
      i++;
    }
    return lhm;
  }
}
