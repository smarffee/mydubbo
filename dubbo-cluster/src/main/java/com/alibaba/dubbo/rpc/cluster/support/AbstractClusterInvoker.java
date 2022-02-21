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
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.cluster.Directory;
import com.alibaba.dubbo.rpc.cluster.LoadBalance;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AbstractClusterInvoker
 *
 * @author william.liangf
 * @author chao.liuc
 */
public abstract class AbstractClusterInvoker<T> implements Invoker<T> {

    private static final Logger logger = LoggerFactory
            .getLogger(AbstractClusterInvoker.class);
    protected final Directory<T> directory;

    protected final boolean availablecheck;

    private AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile Invoker<T> stickyInvoker = null;

    public AbstractClusterInvoker(Directory<T> directory) {
        this(directory, directory.getUrl());
    }

    public AbstractClusterInvoker(Directory<T> directory, URL url) {
        if (directory == null)
            throw new IllegalArgumentException("service directory == null");

        this.directory = directory;
        //sticky 需要检测 avaliablecheck 
        this.availablecheck = url.getParameter(Constants.CLUSTER_AVAILABLE_CHECK_KEY, Constants.DEFAULT_CLUSTER_AVAILABLE_CHECK);
    }

    public Class<T> getInterface() {
        return directory.getInterface();
    }

    public URL getUrl() {
        return directory.getUrl();
    }

    public boolean isAvailable() {
        Invoker<T> invoker = stickyInvoker;
        if (invoker != null) {
            return invoker.isAvailable();
        }
        return directory.isAvailable();
    }

    public void destroy() {
        if (destroyed.compareAndSet(false, true)) {
            directory.destroy();
        }
    }

    /**
     * select 方法的主要逻辑集中在了对粘滞连接特性的支持上。
     * 首先是获取 sticky 配置，然后再检测 invokers 列表中是否包含 stickyInvoker，如果不包含，则认为该 stickyInvoker 不可用，此时将其置空。
     * 这里的 invokers 列表可以看做是存活着的服务提供者列表，如果这个列表不包含 stickyInvoker，那自然而然的认为 stickyInvoker 挂了，所以置空。
     * 如果 stickyInvoker 存在于 invokers 列表中，此时要进行下一项检测 — 检测 selected 中是否包含 stickyInvoker。
     * 如果包含的话，说明 stickyInvoker 在此之前没有成功提供服务（但其仍然处于存活状态）。
     * 此时我们认为这个服务不可靠，不应该在重试期间内再次被调用，因此这个时候不会返回该 stickyInvoker。
     * 如果 selected 不包含 stickyInvoker，此时还需要进行可用性检测，比如检测服务提供者网络连通性等。
     * 当可用性检测通过，才可返回 stickyInvoker，否则调用 doSelect 方法选择 Invoker。
     * 如果 sticky 为 true，此时会将 doSelect 方法选出的 Invoker 赋值给 stickyInvoker。
     *
     * 使用loadbalance选择invoker.</br>
     * a)先lb选择，如果在selected列表中 或者 不可用且做检验时，进入下一步(重选),否则直接返回</br>
     * b)重选验证规则：selected > available .保证重选出的结果尽量不在select中，并且是可用的
     *
     * @param selected       已选过的invoker.注意：输入保证不重复
     */
    protected Invoker<T> select(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.size() == 0)
            return null;

        // 获取调用方法名
        String methodName = invocation == null ? "" : invocation.getMethodName();

        // 获取 sticky 配置，sticky 表示粘滞连接。所谓粘滞连接是指让服务消费者尽可能的
        // 调用同一个服务提供者，除非该提供者挂了再进行切换
        boolean sticky = invokers.get(0).getUrl().getMethodParameter(methodName, Constants.CLUSTER_STICKY_KEY, Constants.DEFAULT_CLUSTER_STICKY);
        {
            // 检测 invokers 列表是否包含 stickyInvoker，如果不包含，
            // 说明 stickyInvoker 代表的服务提供者挂了，此时需要将其置空
            //ignore overloaded method
            if (stickyInvoker != null && !invokers.contains(stickyInvoker)) {
                stickyInvoker = null;
            }

            // 在 sticky 为 true，且 stickyInvoker != null 的情况下。如果 selected 包含
            // stickyInvoker，表明 stickyInvoker 对应的服务提供者可能因网络原因未能成功提供服务。
            // 但是该提供者并没挂，此时 invokers 列表中仍存在该服务提供者对应的 Invoker。
            //ignore cucurrent problem
            if (sticky && stickyInvoker != null && (selected == null || !selected.contains(stickyInvoker))) {
                if (availablecheck && stickyInvoker.isAvailable()) {
                    return stickyInvoker;
                }
            }
        }

        // 如果线程走到当前代码处，说明前面的 stickyInvoker 为空，或者不可用。
        // 此时继续调用 doSelect 选择 Invoker
        Invoker<T> invoker = doselect(loadbalance, invocation, invokers, selected);

        // 如果 sticky 为 true，则将负载均衡组件选出的 Invoker 赋值给 stickyInvoker
        if (sticky) {
            stickyInvoker = invoker;
        }

        return invoker;
    }

    /**
     * 主要做了两件事，
     * 第一是，通过负载均衡组件选择 Invoker。
     * 第二是，如果选出来的 Invoker 不稳定，或不可用，此时需要调用 reselect 方法进行重选。
     * 若 reselect 选出来的 Invoker 为空，此时定位 invoker 在 invokers 列表中的位置 index，
     * 然后获取 index + 1 处的 invoker，这也可以看做是重选逻辑的一部分。
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> doselect(LoadBalance loadbalance, Invocation invocation, List<Invoker<T>> invokers, List<Invoker<T>> selected) throws RpcException {
        if (invokers == null || invokers.size() == 0)
            return null;
        if (invokers.size() == 1)
            return invokers.get(0);
        // 如果只有两个invoker，退化成轮循
        if (invokers.size() == 2 && selected != null && selected.size() > 0) {
            return selected.get(0) == invokers.get(0) ? invokers.get(1) : invokers.get(0);
        }

        // 通过负载均衡组件选择 Invoker
        Invoker<T> invoker = loadbalance.select(invokers, getUrl(), invocation);

        //如果 selected中包含（优先判断） 或者 不可用&&availablecheck=true 则重试.
        // 如果 selected 包含负载均衡选择出的 Invoker，或者该 Invoker 无法经过可用性检查，此时进行重选
        if ((selected != null && selected.contains(invoker))
                || (!invoker.isAvailable() && getUrl() != null && availablecheck)) {
            try {
                // 进行重选
                Invoker<T> rinvoker = reselect(loadbalance, invocation, invokers, selected, availablecheck);
                if (rinvoker != null) {
                    // 如果 rinvoker 不为空，则将其赋值给 invoker
                    invoker = rinvoker;
                } else {
                    //看下第一次选的位置，如果不是最后，选+1位置.
                    // rinvoker 为空，定位 invoker 在 invokers 中的位置
                    int index = invokers.indexOf(invoker);
                    try {
                        //最后在避免碰撞
                        // 获取 index + 1 位置处的 Invoker，以下代码等价于：
                        //     invoker = invokers.get((index + 1) % invokers.size());
                        invoker = index < invokers.size() - 1 ? invokers.get(index + 1) : invoker;
                    } catch (Exception e) {
                        logger.warn(e.getMessage() + " may because invokers list dynamic change, ignore.", e);
                    }
                }
            } catch (Throwable t) {
                logger.error("clustor relselect fail reason is :" + t.getMessage() + " if can not slove ,you can set cluster.availablecheck=false in url", t);
            }
        }
        return invoker;
    }

    /**
     * 重选，先从非selected的列表中选择，没有在从selected列表中选择.
     *
     * 做了两件事情，
     * 第一是查找可用的 Invoker，并将其添加到 reselectInvokers 集合中。
     * 第二，如果 reselectInvokers 不为空，则通过负载均衡组件再次进行选择。
     * 其中第一件事情又可进行细分，一开始，reselect 从 invokers 列表中查找有效可用的 Invoker，
     * 若未能找到，此时再到 selected 列表中继续查找。
     *
     * @param loadbalance
     * @param invocation
     * @param invokers
     * @param selected
     * @return
     * @throws RpcException
     */
    private Invoker<T> reselect(LoadBalance loadbalance, Invocation invocation,
                                List<Invoker<T>> invokers, List<Invoker<T>> selected, boolean availablecheck)
            throws RpcException {

        //预先分配一个，这个列表是一定会用到的.
        List<Invoker<T>> reselectInvokers = new ArrayList<Invoker<T>>(invokers.size() > 1 ? (invokers.size() - 1) : invokers.size());

        // 下面的 if-else 分支逻辑有些冗余，pull request #2826 对这段代码进行了简化，可以参考一下
        // 根据 availablecheck 进行不同的处理
        //先从非select中选
        if (availablecheck) { //选isAvailable 的非select
            // 遍历 invokers 列表
            for (Invoker<T> invoker : invokers) {
                // 检测可用性
                if (invoker.isAvailable()) {
                    // 如果 selected 列表不包含当前 invoker，则将其添加到 reselectInvokers 中
                    if (selected == null || !selected.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            // reselectInvokers 不为空，此时通过负载均衡组件进行选择
            if (reselectInvokers.size() > 0) {
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        } else { //选全部非select
            // 不检查 Invoker 可用性
            for (Invoker<T> invoker : invokers) {
                // 如果 selected 列表不包含当前 invoker，则将其添加到 reselectInvokers 中
                if (selected == null || !selected.contains(invoker)) {
                    reselectInvokers.add(invoker);
                }
            }
            if (reselectInvokers.size() > 0) {
                // 通过负载均衡组件进行选择
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        //最后从select中选可用的. 
        {
            // 若线程走到此处，说明 reselectInvokers 集合为空，此时不会调用负载均衡组件进行筛选。
            // 这里从 selected 列表中查找可用的 Invoker，并将其添加到 reselectInvokers 集合中
            if (selected != null) {
                for (Invoker<T> invoker : selected) {
                    if ((invoker.isAvailable()) //优先选available
                            && !reselectInvokers.contains(invoker)) {
                        reselectInvokers.add(invoker);
                    }
                }
            }
            if (reselectInvokers.size() > 0) {
                // 再次进行选择，并返回选择结果
                return loadbalance.select(reselectInvokers, getUrl(), invocation);
            }
        }
        return null;
    }

    public Result invoke(final Invocation invocation) throws RpcException {

        checkWhetherDestroyed();

        LoadBalance loadbalance;

        // 列举 Invoker
        List<Invoker<T>> invokers = list(invocation);
        if (invokers != null && invokers.size() > 0) {
            // 加载 LoadBalance
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(invokers.get(0).getUrl()
                    .getMethodParameter(invocation.getMethodName(), Constants.LOADBALANCE_KEY, Constants.DEFAULT_LOADBALANCE));
        } else {
            loadbalance = ExtensionLoader.getExtensionLoader(LoadBalance.class).getExtension(Constants.DEFAULT_LOADBALANCE);
        }

        RpcUtils.attachInvocationIdIfAsync(getUrl(), invocation);

        // 调用 doInvoke 进行后续操作
        return doInvoke(invocation, invokers, loadbalance);
    }

    protected void checkWhetherDestroyed() {

        if (destroyed.get()) {
            throw new RpcException("Rpc cluster invoker for " + getInterface() + " on consumer " + NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion()
                    + " is now destroyed! Can not invoke any more.");
        }
    }

    @Override
    public String toString() {
        return getInterface() + " -> " + getUrl().toString();
    }

    protected void checkInvokers(List<Invoker<T>> invokers, Invocation invocation) {
        if (invokers == null || invokers.size() == 0) {
            throw new RpcException("Failed to invoke the method "
                    + invocation.getMethodName() + " in the service " + getInterface().getName()
                    + ". No provider available for the service " + directory.getUrl().getServiceKey()
                    + " from registry " + directory.getUrl().getAddress()
                    + " on the consumer " + NetUtils.getLocalHost()
                    + " using the dubbo version " + Version.getVersion()
                    + ". Please check if the providers have been started and registered.");
        }
    }

    // 抽象方法，由子类实现
    protected abstract Result doInvoke(Invocation invocation, List<Invoker<T>> invokers,
                                       LoadBalance loadbalance) throws RpcException;

    protected List<Invoker<T>> list(Invocation invocation) throws RpcException {
        // 调用 Directory 的 list 方法列举 Invoker
        List<Invoker<T>> invokers = directory.list(invocation);
        return invokers;
    }
}