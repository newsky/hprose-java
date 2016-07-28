/**********************************************************\
|                                                          |
|                          hprose                          |
|                                                          |
| Official WebSite: http://www.hprose.com/                 |
|                   http://www.hprose.org/                 |
|                                                          |
\**********************************************************/
/**********************************************************\
 *                                                        *
 * HproseService.java                                     *
 *                                                        *
 * hprose service class for Java.                         *
 *                                                        *
 * LastModified: Jul 28, 2016                             *
 * Author: Ma Bingyao <andot@hprose.com>                  *
 *                                                        *
\**********************************************************/
package hprose.server;

import hprose.common.HandlerManager;
import hprose.common.HproseContext;
import hprose.common.HproseException;
import hprose.common.HproseFilter;
import hprose.common.HproseMethod;
import hprose.common.HproseMethods;
import hprose.common.HproseResultMode;
import hprose.io.ByteBufferStream;
import hprose.io.HproseMode;
import static hprose.io.HproseTags.TagArgument;
import static hprose.io.HproseTags.TagCall;
import static hprose.io.HproseTags.TagEnd;
import static hprose.io.HproseTags.TagError;
import static hprose.io.HproseTags.TagFunctions;
import static hprose.io.HproseTags.TagList;
import static hprose.io.HproseTags.TagOpenbrace;
import static hprose.io.HproseTags.TagResult;
import static hprose.io.HproseTags.TagTrue;
import hprose.io.serialize.Writer;
import hprose.io.unserialize.Reader;
import hprose.util.StrUtil;
import hprose.util.concurrent.Action;
import hprose.util.concurrent.AsyncFunc;
import hprose.util.concurrent.Call;
import hprose.util.concurrent.Func;
import hprose.util.concurrent.Promise;
import hprose.util.concurrent.Reducer;
import hprose.util.concurrent.Threads;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class HproseService extends HandlerManager implements HproseClients {

    private final static ScheduledExecutorService timerService = Executors.newSingleThreadScheduledExecutor();

    static {
        Threads.registerShutdownHandler(new Runnable() {
            public void run() {
                timerService.shutdownNow();
            }
        });
    }

    private static class InvalidRequestException extends Exception {}

    private final static InvalidRequestException invalidRequestException = new InvalidRequestException();

    public HproseService() {
        add("call", new Callable<Integer>() {
            private final AtomicInteger next = new AtomicInteger(0);
            public Integer call() throws Exception {
                int nextId = next.getAndIncrement();
                if (nextId >= 0) {
                    return nextId;
                }
                else {
                    next.set(1);
                    return 0;
                }
            }
        }, "#", true);
    }

    private final ArrayList<HproseFilter> filters = new ArrayList<HproseFilter>();
    private HproseMode mode = HproseMode.MemberMode;
    private boolean debugEnabled = false;
    private int errorDelay = 10000;
    protected HproseServiceEvent event = null;
    protected HproseMethods globalMethods = null;
    private final static ThreadLocal<ServiceContext> currentContext = new ThreadLocal<ServiceContext>();

    public static ServiceContext getCurrentContext() {
        return currentContext.get();
    }

    public HproseMethods getGlobalMethods() {
        if (globalMethods == null) {
            globalMethods = new HproseMethods();
        }
        return globalMethods;
    }

    public void setGlobalMethods(HproseMethods methods) {
        this.globalMethods = methods;
    }

    public final HproseMode getMode() {
        return mode;
    }

    public final void setMode(HproseMode mode) {
        this.mode = mode;
    }

    public final boolean isDebugEnabled() {
        return debugEnabled;
    }

    public final void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public int getErrorDelay() {
        return errorDelay;
    }

    public void setErrorDelay(int errorDelay) {
        this.errorDelay = errorDelay;
    }

    public final HproseServiceEvent getEvent() {
        return this.event;
    }

    public final void setEvent(HproseServiceEvent event) {
        this.event = event;
    }

    public final HproseFilter getFilter() {
        if (filters.isEmpty()) {
            return null;
        }
        return filters.get(0);
    }

    public final void setFilter(HproseFilter filter) {
        if (!filters.isEmpty()) {
            filters.clear();
        }
        if (filter != null) {
            filters.add(filter);
        }
    }

    public final void addFilter(HproseFilter filter) {
        if (filter != null) {
            filters.add(filter);
        }
    }

    public final boolean removeFilter(HproseFilter filter) {
        return filters.remove(filter);
    }

    public final void add(Method method, Object obj, String aliasName) {
        getGlobalMethods().addMethod(method, obj, aliasName);
    }

    public final void add(Method method, Object obj, String aliasName, HproseResultMode mode) {
        getGlobalMethods().addMethod(method, obj, aliasName, mode);
    }

    public final void add(Method method, Object obj, String aliasName, boolean simple) {
        getGlobalMethods().addMethod(method, obj, aliasName, simple);
    }

    public final void add(Method method, Object obj, String aliasName, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethod(method, obj, aliasName, mode, simple);
    }

    public final void add(Method method, Object obj, String aliasName, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethod(method, obj, aliasName, mode, simple, oneway);
    }

    public final void add(Method method, Object obj) {
        getGlobalMethods().addMethod(method, obj);
    }

    public final void add(Method method, Object obj, HproseResultMode mode) {
        getGlobalMethods().addMethod(method, obj, mode);
    }

    public final void add(Method method, Object obj, boolean simple) {
        getGlobalMethods().addMethod(method, obj, simple);
    }

    public final void add(Method method, Object obj, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethod(method, obj, mode, simple);
    }

    public final void add(Method method, Object obj, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethod(method, obj, mode, simple, oneway);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, String aliasName) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, aliasName);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, String aliasName, HproseResultMode mode) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, aliasName, mode);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, String aliasName, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, aliasName, simple);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, String aliasName, HproseResultMode mode, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, aliasName, mode, simple);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, String aliasName, HproseResultMode mode, boolean simple, boolean oneway) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, aliasName, mode, simple, oneway);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, String aliasName) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, aliasName);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, String aliasName, HproseResultMode mode) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, aliasName, mode);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, String aliasName, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, aliasName, simple);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, String aliasName, HproseResultMode mode, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, aliasName, mode, simple);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, String aliasName, HproseResultMode mode, boolean simple, boolean oneway) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, aliasName, mode, simple, oneway);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, HproseResultMode mode) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, mode);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, simple);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, HproseResultMode mode, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, mode, simple);
    }

    public final void add(String methodName, Object obj, Class<?>[] paramTypes, HproseResultMode mode, boolean simple, boolean oneway) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, obj, paramTypes, mode, simple, oneway);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, HproseResultMode mode) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, mode);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, simple);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, HproseResultMode mode, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, mode, simple);
    }

    public final void add(String methodName, Class<?> type, Class<?>[] paramTypes, HproseResultMode mode, boolean simple, boolean oneway) throws NoSuchMethodException {
        getGlobalMethods().addMethod(methodName, type, paramTypes, mode, simple, oneway);
    }

    public final void add(String methodName, Object obj, String aliasName) {
        getGlobalMethods().addMethod(methodName, obj, aliasName);
    }

    public final void add(String methodName, Object obj, String aliasName, HproseResultMode mode) {
        getGlobalMethods().addMethod(methodName, obj, aliasName, mode);
    }

    public final void add(String methodName, Object obj, String aliasName, boolean simple) {
        getGlobalMethods().addMethod(methodName, obj, aliasName, simple);
    }

    public final void add(String methodName, Object obj, String aliasName, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethod(methodName, obj, aliasName, mode, simple);
    }

    public final void add(String methodName, Object obj, String aliasName, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethod(methodName, obj, aliasName, mode, simple, oneway);
    }

    public final void add(String methodName, Class<?> type, String aliasName) {
        getGlobalMethods().addMethod(methodName, type, aliasName);
    }

    public final void add(String methodName, Class<?> type, String aliasName, HproseResultMode mode) {
        getGlobalMethods().addMethod(methodName, type, aliasName, mode);
    }

    public final void add(String methodName, Class<?> type, String aliasName, boolean simple) {
        getGlobalMethods().addMethod(methodName, type, aliasName, simple);
    }

    public final void add(String methodName, Class<?> type, String aliasName, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethod(methodName, type, aliasName, mode, simple);
    }

    public final void add(String methodName, Class<?> type, String aliasName, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethod(methodName, type, aliasName, mode, simple, oneway);
    }

    public final void add(String methodName, Object obj) {
        getGlobalMethods().addMethod(methodName, obj);
    }

    public final void add(String methodName, Object obj, HproseResultMode mode) {
        getGlobalMethods().addMethod(methodName, obj, mode);
    }

    public final void add(String methodName, Object obj, boolean simple) {
        getGlobalMethods().addMethod(methodName, obj, simple);
    }

    public final void add(String methodName, Object obj, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethod(methodName, obj, mode, simple);
    }

    public final void add(String methodName, Object obj, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethod(methodName, obj, mode, simple, oneway);
    }

    public final void add(String methodName, Class<?> type) {
        getGlobalMethods().addMethod(methodName, type);
    }

    public final void add(String methodName, Class<?> type, HproseResultMode mode) {
        getGlobalMethods().addMethod(methodName, type, mode);
    }

    public final void add(String methodName, Class<?> type, boolean simple) {
        getGlobalMethods().addMethod(methodName, type, simple);
    }

    public final void add(String methodName, Class<?> type, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethod(methodName, type, mode, simple);
    }

    public final void add(String methodName, Class<?> type, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethod(methodName, type, mode, simple, oneway);
    }

    public final void add(String[] methodNames, Object obj, String[] aliasNames) {
        getGlobalMethods().addMethods(methodNames, obj, aliasNames);
    }

    public final void add(String[] methodNames, Object obj, String[] aliasNames, HproseResultMode mode) {
        getGlobalMethods().addMethods(methodNames, obj, aliasNames, mode);
    }

    public final void add(String[] methodNames, Object obj, String[] aliasNames, boolean simple) {
        getGlobalMethods().addMethods(methodNames, obj, aliasNames, simple);
    }

    public final void add(String[] methodNames, Object obj, String[] aliasNames, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethods(methodNames, obj, aliasNames, mode, simple);
    }

    public final void add(String[] methodNames, Object obj, String[] aliasNames, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethods(methodNames, obj, aliasNames, mode, simple, oneway);
    }

    public final void add(String[] methodNames, Object obj, String aliasPrefix) {
        getGlobalMethods().addMethods(methodNames, obj, aliasPrefix);
    }

    public final void add(String[] methodNames, Object obj, String aliasPrefix, HproseResultMode mode) {
        getGlobalMethods().addMethods(methodNames, obj, aliasPrefix, mode);
    }

    public final void add(String[] methodNames, Object obj, String aliasPrefix, boolean simple) {
        getGlobalMethods().addMethods(methodNames, obj, aliasPrefix, simple);
    }

    public final void add(String[] methodNames, Object obj, String aliasPrefix, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethods(methodNames, obj, aliasPrefix, mode, simple);
    }

    public final void add(String[] methodNames, Object obj, String aliasPrefix, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethods(methodNames, obj, aliasPrefix, mode, simple, oneway);
    }

    public final void add(String[] methodNames, Object obj) {
        getGlobalMethods().addMethods(methodNames, obj);
    }

    public final void add(String[] methodNames, Object obj, HproseResultMode mode) {
        getGlobalMethods().addMethods(methodNames, obj, mode);
    }

    public final void add(String[] methodNames, Object obj, boolean simple) {
        getGlobalMethods().addMethods(methodNames, obj, simple);
    }

    public final void add(String[] methodNames, Object obj, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethods(methodNames, obj, mode, simple);
    }

    public final void add(String[] methodNames, Object obj, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethods(methodNames, obj, mode, simple, oneway);
    }

    public final void add(String[] methodNames, Class<?> type, String[] aliasNames) {
        getGlobalMethods().addMethods(methodNames, type, aliasNames);
    }

    public final void add(String[] methodNames, Class<?> type, String[] aliasNames, HproseResultMode mode) {
        getGlobalMethods().addMethods(methodNames, type, aliasNames, mode);
    }

    public final void add(String[] methodNames, Class<?> type, String[] aliasNames, boolean simple) {
        getGlobalMethods().addMethods(methodNames, type, aliasNames, simple);
    }

    public final void add(String[] methodNames, Class<?> type, String[] aliasNames, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethods(methodNames, type, aliasNames, mode, simple);
    }

    public final void add(String[] methodNames, Class<?> type, String[] aliasNames, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethods(methodNames, type, aliasNames, mode, simple, oneway);
    }

    public final void add(String[] methodNames, Class<?> type, String aliasPrefix) {
        getGlobalMethods().addMethods(methodNames, type, aliasPrefix);
    }

    public final void add(String[] methodNames, Class<?> type, String aliasPrefix, HproseResultMode mode) {
        getGlobalMethods().addMethods(methodNames, type, aliasPrefix, mode);
    }

    public final void add(String[] methodNames, Class<?> type, String aliasPrefix, boolean simple) {
        getGlobalMethods().addMethods(methodNames, type, aliasPrefix, simple);
    }

    public final void add(String[] methodNames, Class<?> type, String aliasPrefix, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethods(methodNames, type, aliasPrefix, mode, simple);
    }

    public final void add(String[] methodNames, Class<?> type, String aliasPrefix, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethods(methodNames, type, aliasPrefix, mode, simple, oneway);
    }

    public final void add(String[] methodNames, Class<?> type) {
        getGlobalMethods().addMethods(methodNames, type);
    }

    public final void add(String[] methodNames, Class<?> type, HproseResultMode mode) {
        getGlobalMethods().addMethods(methodNames, type, mode);
    }

    public final void add(String[] methodNames, Class<?> type, boolean simple) {
        getGlobalMethods().addMethods(methodNames, type, simple);
    }

    public final void add(String[] methodNames, Class<?> type, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addMethods(methodNames, type, mode, simple);
    }

    public final void add(String[] methodNames, Class<?> type, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addMethods(methodNames, type, mode, simple, oneway);
    }

    public final void add(Object obj, Class<?> type, String aliasPrefix) {
        getGlobalMethods().addInstanceMethods(obj, type, aliasPrefix);
    }

    public final void add(Object obj, Class<?> type, String aliasPrefix, HproseResultMode mode) {
        getGlobalMethods().addInstanceMethods(obj, type, aliasPrefix, mode);
    }

    public final void add(Object obj, Class<?> type, String aliasPrefix, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, type, aliasPrefix, simple);
    }

    public final void add(Object obj, Class<?> type, String aliasPrefix, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, type, aliasPrefix, mode, simple);
    }

    public final void add(Object obj, Class<?> type, String aliasPrefix, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addInstanceMethods(obj, type, aliasPrefix, mode, simple, oneway);
    }

    public final void add(Object obj, Class<?> type) {
        getGlobalMethods().addInstanceMethods(obj, type);
    }

    public final void add(Object obj, Class<?> type, HproseResultMode mode) {
        getGlobalMethods().addInstanceMethods(obj, type, mode);
    }

    public final void add(Object obj, Class<?> type, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, type, simple);
    }

    public final void add(Object obj, Class<?> type, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, type, mode, simple);
    }

    public final void add(Object obj, Class<?> type, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addInstanceMethods(obj, type, mode, simple, oneway);
    }

    public final void add(Object obj, String aliasPrefix) {
        getGlobalMethods().addInstanceMethods(obj, aliasPrefix);
    }

    public final void add(Object obj, String aliasPrefix, HproseResultMode mode) {
        getGlobalMethods().addInstanceMethods(obj, aliasPrefix, mode);
    }

    public final void add(Object obj, String aliasPrefix, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, aliasPrefix, simple);
    }

    public final void add(Object obj, String aliasPrefix, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, aliasPrefix, mode, simple);
    }

    public final void add(Object obj, String aliasPrefix, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addInstanceMethods(obj, aliasPrefix, mode, simple, oneway);
    }

    public final void add(Object obj) {
        getGlobalMethods().addInstanceMethods(obj);
    }

    public final void add(Object obj, HproseResultMode mode) {
        getGlobalMethods().addInstanceMethods(obj, mode);
    }

    public final void add(Object obj, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, simple);
    }

    public final void add(Object obj, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addInstanceMethods(obj, mode, simple);
    }

    public final void add(Object obj, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addInstanceMethods(obj, mode, simple, oneway);
    }

    public final void add(Class<?> type, String aliasPrefix) {
        getGlobalMethods().addStaticMethods(type, aliasPrefix);
    }

    public final void add(Class<?> type, String aliasPrefix, HproseResultMode mode) {
        getGlobalMethods().addStaticMethods(type, aliasPrefix, mode);
    }

    public final void add(Class<?> type, String aliasPrefix, boolean simple) {
        getGlobalMethods().addStaticMethods(type, aliasPrefix, simple);
    }

    public final void add(Class<?> type, String aliasPrefix, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addStaticMethods(type, aliasPrefix, mode, simple);
    }

    public final void add(Class<?> type, String aliasPrefix, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addStaticMethods(type, aliasPrefix, mode, simple, oneway);
    }

    public final void add(Class<?> type) {
        getGlobalMethods().addStaticMethods(type);
    }

    public final void add(Class<?> type, HproseResultMode mode) {
        getGlobalMethods().addStaticMethods(type, mode);
    }

    public final void add(Class<?> type, boolean simple) {
        getGlobalMethods().addStaticMethods(type, simple);
    }

    public final void add(Class<?> type, HproseResultMode mode, boolean simple) {
        getGlobalMethods().addStaticMethods(type, mode, simple);
    }

    public final void add(Class<?> type, HproseResultMode mode, boolean simple, boolean oneway) {
        getGlobalMethods().addStaticMethods(type, mode, simple, oneway);
    }

    public final void addMissingMethod(String methodName, Object obj) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, obj);
    }

    public final void addMissingMethod(String methodName, Object obj, HproseResultMode mode) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, obj, mode);
    }

    public final void addMissingMethod(String methodName, Object obj, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, obj, simple);
    }

    public final void addMissingMethod(String methodName, Object obj, HproseResultMode mode, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, obj, mode, simple);
    }

    public final void addMissingMethod(String methodName, Object obj, HproseResultMode mode, boolean simple, boolean oneway) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, obj, mode, simple, oneway);
    }

    public final void addMissingMethod(String methodName, Class<?> type) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, type);
    }

    public final void addMissingMethod(String methodName, Class<?> type, HproseResultMode mode) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, type, mode);
    }

    public final void addMissingMethod(String methodName, Class<?> type, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, type, simple);
    }

    public final void addMissingMethod(String methodName, Class<?> type, HproseResultMode mode, boolean simple) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, type, mode, simple);
    }

    public final void addMissingMethod(String methodName, Class<?> type, HproseResultMode mode, boolean simple, boolean oneway) throws NoSuchMethodException {
        getGlobalMethods().addMissingMethod(methodName, type, mode, simple, oneway);
    }

    private ByteBuffer outputFilter(ByteBuffer response, ServiceContext context) {
        if (response.position() != 0) {
            response.flip();
        }
        for (int i = 0, n = filters.size(); i < n; ++i) {
            response = filters.get(i).outputFilter(response, context);
            if (response.position() != 0) {
                response.flip();
            }
        }
        return response;
    }

    private ByteBuffer inputFilter(ByteBuffer request, ServiceContext context) {
        if (request.position() != 0) {
            request.flip();
        }
        for (int i = filters.size() - 1; i >= 0; --i) {
            request = filters.get(i).inputFilter(request, context);
            if (request.position() != 0) {
                request.flip();
            }
        }
        return request;
    }

    private String getErrorMessage(Throwable e) {
        if (debugEnabled) {
            StackTraceElement[] st = e.getStackTrace();
            StringBuffer es = new StringBuffer(e.toString()).append("\r\n");
            for (int i = 0, n = st.length; i < n; ++i) {
                es.append(st[i].toString()).append("\r\n");
            }
            return es.toString();
        }
        return e.toString();
    }

    private ByteBuffer sendError(Throwable e, ServiceContext context) {
        try {
            if (event != null) {
                Throwable ex = event.onSendError(e, context);
                if (ex != null) {
                    e = ex;
                }
            }
        }
        catch (Throwable ex) {
            e = ex;
        }
        try {
            ByteBufferStream data = new ByteBufferStream();
            Writer writer = new Writer(data.getOutputStream(), mode, true);
            data.write(TagError);
            writer.writeString(getErrorMessage(e));
            data.flip();
            return data.buffer;
        }
        catch (IOException ex) {
            fireErrorEvent(ex, context);
        }
        return null;
    }

    private ByteBuffer endError(Throwable e, ServiceContext context) {
        ByteBufferStream data = new ByteBufferStream();
        data.write(sendError(e, context));
        data.write(TagEnd);
        data.flip();
        return data.buffer;
    }

    protected Object[] fixArguments(Type[] argumentTypes, Object[] arguments, ServiceContext context) {
        int count = arguments.length;
        if (argumentTypes.length != count) {
            Object[] args = new Object[argumentTypes.length];
            System.arraycopy(arguments, 0, args, 0, count);
            Class<?> argType = (Class<?>) argumentTypes[count];
            if (argType.equals(HproseContext.class) || argType.equals(ServiceContext.class)) {
                args[count] = context;
            }
            return args;
        }
        return arguments;
    }

    private ByteBuffer doOutput(Object[] args, Object result, ServiceContext context) throws  IOException, InterruptedException, ExecutionException {
        ByteBufferStream data = new ByteBufferStream();
        HproseMethod remoteMethod = context.getRemoteMethod();
        if (result instanceof Future) {
            result = ((Future)result).get();
        }
        if (remoteMethod.mode == HproseResultMode.RawWithEndTag) {
            data.write((byte[])result);
            return data.buffer;
        }
        else if (remoteMethod.mode == HproseResultMode.Raw) {
            data.write((byte[])result);
        }
        else {
            data.write(TagResult);
            boolean simple = remoteMethod.simple;
            Writer writer = new Writer(data.getOutputStream(), mode, simple);
            if (remoteMethod.mode == HproseResultMode.Serialized) {
                data.write((byte[])result);
            }
            else {
                writer.serialize(result);
            }
            if (context.isByref()) {
                data.write(TagArgument);
                writer.reset();
                writer.writeArray(args);
            }
        }
        data.flip();
        return data.buffer;
    }

    private Object beforeInvoke(final String name, final Object[] args, final ServiceContext context) {
        try {
            if (event != null) {
                event.onBeforeInvoke(name, args, context.isByref(), context);
            }
            return invokeHandler.handle(name, args, context).then(new Func<ByteBuffer, Object>() {
                public ByteBuffer call(Object result) throws Throwable {
                    if (result instanceof Throwable) {
                        throw (Throwable)result;
                    }
                    if (event != null) {
                        event.onAfterInvoke(name, args, context.isByref(), result, context);
                    }
                    return doOutput(args, result, context);
                }
            }).catchError(new Func<ByteBuffer, Throwable>() {
                public ByteBuffer call(Throwable e) throws Throwable {
                    return sendError(e, context);
                }
            });
        }
        catch (Throwable e) {
            return sendError(e, context);
        }
    }

    private Object callService(String name, Object[] args, ServiceContext context) throws Throwable {
        HproseMethod remoteMethod = context.getRemoteMethod();
        try {
            if (context.isMissingMethod()) {
                return remoteMethod.method.invoke(remoteMethod.obj, new Object[]{name, args});
            }
            else {
                Object[] arguments = fixArguments(remoteMethod.paramTypes, args, context);
                Object result = remoteMethod.method.invoke(remoteMethod.obj, arguments);
                if (context.isByref()) {
                    System.arraycopy(arguments, 0, args, 0, args.length);
                }
                return result;
            }
        }
        catch (Throwable ex) {
            Throwable e = ex.getCause();
            if (e != null) {
                throw e;
            }
            throw ex;
        }
    }

    @Override
    protected Promise<Object> invokeHandler(String name, Object[] args, HproseContext context) {
        return invokeHandler(name, args, (ServiceContext)context);
    }

    @Override
    protected Promise<ByteBuffer> beforeFilterHandler(ByteBuffer request, HproseContext context) {
        return beforeFilter(request, (ServiceContext)context);
    }

    @Override
    protected Promise<ByteBuffer> afterFilterHandler(ByteBuffer request, HproseContext context) {
        return afterFilter(request, (ServiceContext)context);
    }

    private Promise<Object> invokeHandler(final String name, final Object[] args, final ServiceContext context) {
        boolean oneway = context.getRemoteMethod().oneway;
        if (oneway) {
            timerService.execute(new Runnable() {
                public void run() {
                    try {
                        callService(name, args, context);
                    }
                    catch (Throwable e) {}
                }
            });
            return Promise.value(null);
        }
        return Promise.sync(new Call<Object>() {
            public Object call() throws Throwable {
                return callService(name, args, context);
            }
        });
    }

    @SuppressWarnings("unchecked")
    protected Promise<ByteBuffer> doInvoke(ByteBufferStream stream, ServiceContext context) throws IOException {
        HproseMethods methods = context.getMethods();
        Reader reader = new Reader(stream.getInputStream(), mode);
        ArrayList<Object> results = new ArrayList<Object>();
        int tag;
        do {
            reader.reset();
            String name = reader.readString();
            String aliasname = name.toLowerCase();
            HproseMethod remoteMethod = null;
            Object[] args;
            tag = reader.checkTags((char) TagList + "" +
                                   (char) TagEnd + "" +
                                   (char) TagCall);
            if (tag == TagList) {
                reader.reset();
                int count = reader.readInt(TagOpenbrace);
                if (methods != null) {
                    remoteMethod = methods.get(aliasname, count);
                }
                if (remoteMethod == null) {
                    remoteMethod = getGlobalMethods().get(aliasname, count);
                }
                if (remoteMethod == null) {
                    args = reader.readArray(count);
                }
                else {
                    args = new Object[count];
                    reader.readArray(remoteMethod.paramTypes, args, count);
                }
                tag = reader.checkTags((char) TagTrue + "" +
                                       (char) TagEnd + "" +
                                       (char) TagCall);
                if (tag == TagTrue) {
                    context.setByref(true);
                    tag = reader.checkTags((char) TagEnd + "" +
                                           (char) TagCall);
                }
            }
            else {
                if (methods != null) {
                    remoteMethod = methods.get(aliasname, 0);
                }
                if (remoteMethod == null) {
                    remoteMethod = getGlobalMethods().get(aliasname, 0);
                }
                args = new Object[0];
            }
            if (remoteMethod == null) {
                if (methods != null) {
                    remoteMethod = methods.get("*", 2);
                }
                if (remoteMethod == null) {
                    remoteMethod = getGlobalMethods().get("*", 2);
                }
                context.setMissingMethod(true);
            }
            else {
                context.setMissingMethod(false);
            }
            if (remoteMethod == null) {
                results.add(sendError(new NoSuchMethodError("Can't find this method " + name), context));
            }
            else {
                context.setRemoteMethod(remoteMethod);
                results.add(beforeInvoke(name, args, context));
            }
        } while (tag == TagCall);
        return Promise.reduce(results.toArray(),
            new Reducer<ByteBufferStream, ByteBuffer>() {
                public ByteBufferStream call(ByteBufferStream output, ByteBuffer result, int index) throws Throwable {
                    output.write(result);
                    return output;
                }
            },
            new ByteBufferStream()).then(new Func<ByteBuffer, ByteBufferStream>() {
                public ByteBuffer call(ByteBufferStream data) throws Throwable {
                    data.write(TagEnd);
                    return data.buffer;
                }
            }
        );
    }

    protected ByteBufferStream doFunctionList(ServiceContext context) throws IOException {
        HproseMethods methods = context.getMethods();
        ArrayList<String> names = new ArrayList<String>();
        names.addAll(getGlobalMethods().getAllNames());
        if (methods != null) {
            names.addAll(methods.getAllNames());
        }
        ByteBufferStream data = new ByteBufferStream();
        Writer writer = new Writer(data.getOutputStream(), mode, true);
        data.write(TagFunctions);
        writer.writeList(names);
        data.write(TagEnd);
        return data;
    }

    private Promise<ByteBuffer> afterFilter(ByteBuffer request, ServiceContext context) {
        try {
            ByteBufferStream stream = new ByteBufferStream(request);
            switch (stream.read()) {
                case TagCall:
                    return doInvoke(stream, context);
                case TagEnd:
                    return Promise.value(doFunctionList(context).buffer);
                default:
                    throw new HproseException("Wrong Request: \r\n" + StrUtil.toString(stream));
            }
        }
        catch (IOException e) {
            return Promise.error(e);
        }
    }

    private Promise<ByteBuffer> delayError(Throwable e, ServiceContext context) {
        ByteBuffer error = endError(e, context);
        if (errorDelay > 0) {
            return Promise.delayed(errorDelay, error);
        }
        else {
            return Promise.value(error);
        }
    }

    @SuppressWarnings("unchecked")
    private Promise<ByteBuffer> beforeFilter(ByteBuffer request, final ServiceContext context) {
        Promise<ByteBuffer> response;
        try {
            request = inputFilter(request, context);
            response = afterFilterHandler.handle(request, context).catchError(new AsyncFunc<ByteBuffer, Throwable>() {
                public Promise<ByteBuffer> call(Throwable e) throws Throwable {
                    return delayError(e, context);
                }
            });
        }
        catch (Throwable e) {
            response = delayError(e, context);
        }
        return response.then(new Func<ByteBuffer, ByteBuffer>() {
            public ByteBuffer call(ByteBuffer value) throws Throwable {
                return outputFilter(value, context);
            }
        });
    }

    protected void fireErrorEvent(Throwable e, ServiceContext context) {
        if (event != null) {
            event.onServerError(e, context);
        }
    }

    protected Promise<ByteBuffer> handle(ByteBuffer buffer, ServiceContext context) {
        currentContext.set(context);
        return beforeFilterHandler.handle(buffer, context).whenComplete(new Runnable() {
            public void run() {
                currentContext.remove();
            }
        });
    }

    protected Promise<ByteBuffer> handle(ByteBuffer buffer, HproseMethods methods, ServiceContext context) {
        context.setMethods(methods);
        return handle(buffer, context);
    }

    private int timeout = 120000;
    private int heartbeat = 3000;
    private PushEvent pushEvent = null;

    public int getTimeout() {
        return timeout;
    }

    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    public int getHeartbeat() {
        return heartbeat;
    }

    public void setHeartbeat(int heartbeat) {
        this.heartbeat = heartbeat;
    }

    public PushEvent getPushEvent() {
        return pushEvent;
    }

    public void setPushEvent(PushEvent pushEvent) {
        this.pushEvent = pushEvent;
    }

    private final ConcurrentHashMap<String, ConcurrentHashMap<Integer, Topic>> allTopics = new ConcurrentHashMap<String, ConcurrentHashMap<Integer, Topic>>();

    private ConcurrentHashMap<Integer, Topic> getTopics(String topic) {
        ConcurrentHashMap<Integer, Topic> topics = allTopics.get(topic);
        if (topics == null) {
            throw new RuntimeException("topic \"" + topic + "\" is not published.");
        }
        return topics;
    }

    private void delTimer(ConcurrentHashMap<Integer, Topic> topics, Integer id) {
        Topic t = topics.get(id);
        if (t != null && t.timer != null) {
            t.timer.cancel(false);
            t.timer = null;
        }
    }

    private void offline(ConcurrentHashMap<Integer, Topic> topics, String topic, Integer id) {
        delTimer(topics, id);
        ConcurrentLinkedQueue<Message> messages = topics.remove(id).messages;
        for (Message message: messages) {
            message.detector.resolve(false);
        }
        if (pushEvent != null) {
            pushEvent.unsubscribe(topic, id, this);
        }
    }

    private void setTimer(final ConcurrentHashMap<Integer, Topic> topics, final String topic, final Integer id) {
        Topic t = topics.get(id);
        if (t != null && t.timer == null) {
            t.timer = timerService.schedule(new Runnable() {
                public void run() {
                    offline(topics, topic, id);
                }
            }, t.heartbeat, TimeUnit.MILLISECONDS);
        }
    }

    private void resetTimer(final ConcurrentHashMap<Integer, Topic> topics, final String topic, final Integer id) {
        delTimer(topics, id);
        setTimer(topics, topic, id);
    }

    private Promise<?> setRequestTimer(final String topic, final Integer id, Promise<?> request, int timeout) {
        final ConcurrentHashMap<Integer, Topic> topics = getTopics(topic);
        if (timeout > 0) {
            return request.timeout(timeout).catchError(new AsyncFunc<Object, Throwable>() {
                public Promise<Object> call(Throwable e) throws Throwable {
                    final Topic t = topics.get(id);
                    if (e instanceof TimeoutException) {
                        new Runnable() {
                            public void run() {
                                t.timer = timerService.schedule(this, t.heartbeat, TimeUnit.MILLISECONDS);
                                if (t.count.get() < 0) {
                                    offline(topics, topic, id);
                                }
                                else {
                                    t.count.decrementAndGet();
                                }
                            }
                        }.run();
                    }
                    else {
                        if (e instanceof InvalidRequestException) {
                            return new Promise<Object>();
                        }
                        t.count.decrementAndGet();
                    }
                    return null;
                }
            });
        }
        return request;
    }

    public final void publish(String topic) {
        publish(topic, -1, -1);
    }

    public final void publish(String topic, int timeout) {
        publish(topic, timeout, -1);
    }

    public final void publish(final String topic, final int timeout, final int heartbeat) {
        final ConcurrentHashMap<Integer, Topic> topics = new ConcurrentHashMap<Integer, Topic>();
        allTopics.put(topic, topics);
        add("call", new Func<Object, Integer>() {
            public Object call(final Integer id) throws Throwable {
                Topic t = topics.get(id);
                if (t != null) {
                    if (t.count.get() < 0) {
                        t.count.set(0);
                    }
                    ConcurrentLinkedQueue<Message> messages = t.messages;
                    if (messages.size() > 0) {
                        Message message = messages.poll();
                        message.detector.resolve(true);
                        resetTimer(topics, topic, id);
                        return message.result;
                    }
                    else {
                        delTimer(topics, id);
                        t.count.incrementAndGet();
                    }
                }
                else {
                    t = new Topic((heartbeat < 0) ? HproseService.this.heartbeat : heartbeat);
                    topics.put(id, t);
                    if (pushEvent != null) {
                        pushEvent.subscribe(topic, id, HproseService.this);
                    }
                }
                if (t.request != null) {
                    t.request.reject(invalidRequestException);
                }
                Promise<Object> request = new Promise<Object>();
                request.complete(new Action<Object>() {
                    public void call(Object result) throws Throwable {
                        Topic t = topics.get(id);
                        t.count.decrementAndGet();
                    }
                });
                t.request = request;
                return setRequestTimer(topic, id, request, (timeout < 0) ? HproseService.this.timeout : timeout);
            }
        }, topic);
    }

    public final void publish(String[] topics) {
        publish(topics, -1, -1);
    }

    public final void publish(String[] topics, int timeout) {
        publish(topics, timeout, -1);
    }

    public final void publish(String[] topics, int timeout, int heartbeat) {
        for (int i = 0, n = topics.length; i < n; ++i) {
            publish(topics[i], timeout, heartbeat);
        }
    }

    public final Integer[] idlist(String topic) {
        return getTopics(topic).keySet().toArray(new Integer[0]);
    }

    public final boolean exist(String topic, Integer id) {
        return getTopics(topic).containsKey(id);
    }

    public final void broadcast(String topic, Object result) {
        multicast(topic, idlist(topic), result);
    }

    public final void broadcast(String topic, Object result, Action<Integer[]> callback) {
        multicast(topic, idlist(topic), result, callback);
    }

    public final void multicast(String topic, Integer[] ids, Object result) {
        for (int i = 0, n = ids.length; i < n; ++i) {
            push(topic, ids[i], result);
        }
    }

    public final void multicast(String topic, Integer[] ids, Object result, Action<Integer[]> callback) {
        if (callback == null) {
            multicast(topic, ids, result);
            return;
        }
        int n = ids.length;
        List<Integer> sent = Collections.synchronizedList(new ArrayList<Integer>(n));
        AtomicInteger count = new AtomicInteger(n);
        for (int i = 0; i < n; ++i) {
            Integer id = ids[i];
            if (id != null) {
                push(topic, id, result).then(check(sent, id, count, callback));
            }
            else {
                count.decrementAndGet();
            }
        }
    }

    private Action<Boolean> check(final List<Integer> sent, final Integer id, final AtomicInteger count, final Action<Integer[]> callback) {
        return new Action<Boolean>() {
            public void call(Boolean success) throws Throwable {
                if (success) {
                    sent.add(id);
                }
                if (count.decrementAndGet() == 0) {
                    callback.call(sent.toArray(new Integer[sent.size()]));
                }
            }
        };
    }

    public final void unicast(String topic, Integer id, Object result) {
        push(topic, id, result);
    }

    public final void unicast(String topic, Integer id, Object result, Action<Boolean> callback) {
        Promise<Boolean> detector = push(topic, id, result);
        if (callback != null) {
            detector.then(callback);
        }
    }

    public final Promise<Integer[]> push(String topic, Object result) {
        return push(topic, idlist(topic), result);
    }

    public final Promise<Integer[]> push(String topic, Integer[] ids, Object result) {
        final Promise<Integer[]> detector = new Promise<Integer[]>();
        multicast(topic, ids, result, new Action<Integer[]>() {
            public void call(Integer[] value) throws Throwable {
                detector.resolve(value);
            }
        });
        return detector;
    }

    public final Promise<Boolean> push(String topic, Integer id, Object result) {
        final ConcurrentHashMap<Integer, Topic> topics = getTopics(topic);
        Topic t = topics.get(id);
        if (t == null) {
            return Promise.value(false);
        }
        if (t.request != null) {
            try {
                t.request.resolve(result);
                t.request = null;
                setTimer(topics, topic, id);
                return Promise.value(true);
            }
            catch (NullPointerException e) {}
        }
        Promise<Boolean> detector = new Promise<Boolean>();
        t.messages.add(new Message(detector, result));
        setTimer(topics, topic, id);
        return detector;
    }

}