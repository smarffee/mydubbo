/*
 * Copyright 1999-2011 Alibaba Group.
 *  
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.rpc.cluster.support;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.utils.NamedThreadFactory;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 并行调用，只要一个成功即返回，通常用于实时性要求较高的操作，但需要浪费更多服务资源。
 *
 * ForkingClusterInvoker 会在运行时通过线程池创建多个线程，并发调用多个服务提供者。
 * 只要有一个服务提供者成功返回了结果，doInvoke 方法就会立即结束运行。
 * ForkingClusterInvoker 的应用场景是在一些对实时性要求比较高读操作（注意是读操作，并行写操作可能不安全）下使用，但这将会耗费更多的资源。
 *
 * <p>
 * <a href="http://en.wikipedia.org/wiki/Fork_(topology)">Fork</a>
 *
 * @author william.liangf
 */
public class ForkingClusterInvoker<T> extends AbstractClusterInvoker<T> {

    private final ExecutorService executor = Executors.newCachedThreadPool(new NamedThreadFactory("forking-cluster-timer", true));

    public ForkingClusterInvoker(Directory<T> directory) {
        super(directory);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Result doInvoke(final Invocation invocation, List<Invoker<T>> invokers, LoadBalance loadbalance) throws RpcException {
        checkInvokers(invokers, invocation);
        final List<Invoker<T>> selected;

        // 获取 forks 配置
        final int forks = getUrl().getParameter(Constants.FORKS_KEY, Constants.DEFAULT_FORKS);

        // 获取超时配置
        final int timeout = getUrl().getParameter(Constants.TIMEOUT_KEY, Constants.DEFAULT_TIMEOUT);

        // 如果 forks 配置不合理，则直接将 invokers 赋值给 selected
        if (forks <= 0 || forks >= invokers.size()) {
            selected = invokers;
        } else {
            selected = new ArrayList<Invoker<T>>();

            // 循环选出 forks 个 Invoker，并添加到 selected 中
            for (int i = 0; i < forks; i++) {
                //在invoker列表(排除selected)后,如果没有选够,则存在重复循环问题.见select实现.
                // 选择 Invoker
                Invoker<T> invoker = select(loadbalance, invocation, invokers, selected);
                if (!selected.contains(invoker)) {//防止重复添加invoker
                    selected.add(invoker);
                }
            }
        }

        //开始到分割线1之间的代码主要是用于选出 forks 个 Invoker，为接下来的并发调用提供输入。
        // ----------------------✨ 分割线1 ✨---------------------- //

        RpcContext.getContext().setInvokers((List) selected);
        final AtomicInteger count = new AtomicInteger();
        final BlockingQueue<Object> ref = new LinkedBlockingQueue<Object>();

        // 遍历 selected 列表
        for (final Invoker<T> invoker : selected) {
            // 为每个 Invoker 创建一个执行线程
            executor.execute(new Runnable() {
                public void run() {
                    try {
                        // 进行远程调用
                        Result result = invoker.invoke(invocation);
                        // 将结果存到阻塞队列中
                        ref.offer(result);
                    } catch (Throwable e) {
                        int value = count.incrementAndGet();
                        // 仅在 value 大于等于 selected.size() 时，才将异常对象
                        // 放入阻塞队列中，请大家思考一下为什么要这样做。
                        //为什么要在value >= selected.size()的情况下，才将异常对象添加到阻塞队列中？这里来解答一下。
                        // 原因是这样的，在并行调用多个服务提供者的情况下，只要有一个服务提供者能够成功返回结果，而其他全部失败。
                        // 此时 ForkingClusterInvoker 仍应该返回成功的结果，而非抛出异常。
                        // 在value >= selected.size()时将异常对象放入阻塞队列中，
                        // 可以保证异常对象不会出现在正常结果的前面，这样可从阻塞队列中优先取出正常的结果。
                        if (value >= selected.size()) {
                            // 将异常对象存入到阻塞队列中
                            ref.offer(e);
                        }
                    }
                }
            });
        }

        //分割线1和分割线2之间的逻辑通过线程池并发调用多个 Invoker，并将结果存储在阻塞队列中。

        // ----------------------✨ 分割线2 ✨---------------------- //

        //分割线2到方法结尾之间的逻辑主要用于从阻塞队列中获取返回结果，并对返回结果类型进行判断。如果为异常类型，则直接抛出，否则返回。

        try {
            // 从阻塞队列中取出远程调用结果
            Object ret = ref.poll(timeout, TimeUnit.MILLISECONDS);

            // 如果结果类型为 Throwable，则抛出异常
            if (ret instanceof Throwable) {
                Throwable e = (Throwable) ret;
                throw new RpcException(e instanceof RpcException ? ((RpcException) e).getCode() : 0, "Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e.getCause() != null ? e.getCause() : e);
            }

            // 返回结果
            return (Result) ret;
        } catch (InterruptedException e) {
            throw new RpcException("Failed to forking invoke provider " + selected + ", but no luck to perform the invocation. Last error is: " + e.getMessage(), e);
        }
    }
}