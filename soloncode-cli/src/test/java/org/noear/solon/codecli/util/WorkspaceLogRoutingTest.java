/*
 * Copyright 2017-2026 noear.org and authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.noear.solon.codecli.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.noear.solon.codecli.workspace.WorkspaceLogRouter;
import org.slf4j.MDC;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工作区日志分流的归属解析测试。
 *
 * <p>覆盖三条传播链路：作用域打标/还原、子线程继承（logback 1.3 起 MDC 非继承）、
 * Reactor 调度器跳线程传播。</p>
 *
 * @author noear
 */
public class WorkspaceLogRoutingTest {

    private static final String WS_A = "/tmp/ws-alpha";
    private static final String WS_B = "/tmp/ws-beta";

    @AfterEach
    public void tearDown() {
        MDC.clear();
    }

    /**
     * 作用域内归属为目标工作区，退出后还原为启动工作区
     */
    @Test
    public void scope_markAndRestore() {
        String startup = WorkspaceLogRouter.currentWsKey();
        String keyA = LogDirUtil.workspaceKey(WS_A);
        assertNotEquals(keyA, startup, "测试前提：WS_A 不能等于启动工作区");

        Object token = WorkspaceLogRouter.beginScope(WS_A);
        try {
            assertEquals(keyA, WorkspaceLogRouter.currentWsKey());
            assertEquals(keyA, MDC.get(LogDirUtil.WS_KEY));
        } finally {
            WorkspaceLogRouter.endScope(token);
        }

        assertEquals(startup, WorkspaceLogRouter.currentWsKey(), "退出作用域后必须还原");
        assertTrue(MDC.get(LogDirUtil.WS_KEY) == null, "退出作用域后 MDC 不应残留");
    }

    /**
     * 嵌套作用域：内层结束后还原到外层，而不是直接掉回启动工作区
     */
    @Test
    public void scope_nestedRestoreToOuter() {
        String keyA = LogDirUtil.workspaceKey(WS_A);
        String keyB = LogDirUtil.workspaceKey(WS_B);

        Object outer = WorkspaceLogRouter.beginScope(WS_A);
        try {
            Object inner = WorkspaceLogRouter.beginScope(WS_B);
            try {
                assertEquals(keyB, WorkspaceLogRouter.currentWsKey());
            } finally {
                WorkspaceLogRouter.endScope(inner);
            }
            assertEquals(keyA, WorkspaceLogRouter.currentWsKey(), "内层退出应还原到外层工作区");
        } finally {
            WorkspaceLogRouter.endScope(outer);
        }
    }

    /**
     * 子线程继承：logback 1.3 起 MDC 用非继承 ThreadLocal，靠继承式标记兜住
     * （FileWatchService 轮询线程、LSP/MCP 传输线程等都属于这一类）
     */
    @Test
    public void childThread_inheritsWorkspace() throws Exception {
        String keyA = LogDirUtil.workspaceKey(WS_A);
        AtomicReference<String> inChild = new AtomicReference<>();

        Object token = WorkspaceLogRouter.beginScope(WS_A);
        try {
            Thread child = new Thread(() -> inChild.set(WorkspaceLogRouter.currentWsKey()));
            child.start();
            child.join(5000);
        } finally {
            WorkspaceLogRouter.endScope(token);
        }

        assertEquals(keyA, inChild.get(), "作用域内创建的子线程应继承工作区归属");
    }

    /**
     * Reactor 调度器跳线程后仍保留归属（agent 管道主体跑在 boundedElastic 上）
     */
    @Test
    public void reactorScheduler_propagatesWorkspace() throws Exception {
        WorkspaceLogRouter.installMdcPropagation();

        String keyA = LogDirUtil.workspaceKey(WS_A);
        AtomicReference<String> inSource = new AtomicReference<>();
        AtomicReference<String> inDownstream = new AtomicReference<>();
        AtomicReference<String> inNestedThread = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Object token = WorkspaceLogRouter.beginScope(WS_A);
        try {
            Flux.create(sink -> {
                        inSource.set(WorkspaceLogRouter.currentWsKey());
                        //模拟客户端在管道内新建 IO 线程
                        Thread io = new Thread(() -> inNestedThread.set(WorkspaceLogRouter.currentWsKey()));
                        io.start();
                        try {
                            io.join(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        sink.next("x");
                        sink.complete();
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnNext(v -> inDownstream.set(WorkspaceLogRouter.currentWsKey()))
                    .doFinally(s -> latch.countDown())
                    .subscribe();
        } finally {
            WorkspaceLogRouter.endScope(token);
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "管道应在超时前结束");
        assertEquals(keyA, inSource.get(), "subscribeOn 跳线程后应保留工作区归属");
        assertEquals(keyA, inDownstream.get(), "下游算子应保留工作区归属");
        assertEquals(keyA, inNestedThread.get(), "管道内新建线程应继承工作区归属");
    }

    /**
     * 无任何标记时回退到启动工作区（后台/框架线程的既定行为）
     */
    @Test
    public void noMark_fallbackToStartupWorkspace() throws Exception {
        AtomicReference<String> inBareThread = new AtomicReference<>();

        Thread bare = new Thread(() -> inBareThread.set(WorkspaceLogRouter.currentWsKey()));
        bare.start();
        bare.join(5000);

        assertEquals(LogDirUtil.workspaceKey(), inBareThread.get());
    }
}
