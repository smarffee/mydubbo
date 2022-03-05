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
package com.alibaba.dubbo.registry.integration;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.Version;
import com.alibaba.dubbo.common.extension.ExtensionLoader;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;
import com.alibaba.dubbo.common.utils.NetUtils;
import com.alibaba.dubbo.common.utils.StringUtils;
import com.alibaba.dubbo.registry.NotifyListener;
import com.alibaba.dubbo.registry.Registry;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Protocol;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.RpcInvocation;
import com.alibaba.dubbo.rpc.cluster.Cluster;
import com.alibaba.dubbo.rpc.cluster.Configurator;
import com.alibaba.dubbo.rpc.cluster.ConfiguratorFactory;
import com.alibaba.dubbo.rpc.cluster.Router;
import com.alibaba.dubbo.rpc.cluster.RouterFactory;
import com.alibaba.dubbo.rpc.cluster.directory.AbstractDirectory;
import com.alibaba.dubbo.rpc.cluster.directory.StaticDirectory;
import com.alibaba.dubbo.rpc.cluster.support.ClusterUtils;
import com.alibaba.dubbo.rpc.protocol.InvokerWrapper;
import com.alibaba.dubbo.rpc.support.RpcUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RegistryDirectory
 *
 * RegistryDirectory 是一种动态服务目录，实现了 NotifyListener 接口。
 * 当注册中心服务配置发生变化后，RegistryDirectory 可收到与当前服务相关的变化。
 * 收到变更通知后，RegistryDirectory 可根据配置变更信息刷新 Invoker 列表。
 *
 * RegistryDirectory 是一个动态服务目录，会随注册中心配置的变化进行动态调整。
 * 因此 RegistryDirectory 实现了 NotifyListener 接口，通过这个接口获取注册中心变更通知。
 *
 * RegistryDirectory 中有几个比较重要的逻辑，
 * 第一是 Invoker 的列举逻辑，
 * 第二是接收服务配置变更的逻辑，
 * 第三是 Invoker 列表的刷新逻辑。
 *
 * @author william.liangf
 * @author chao.liuc
 */
public class RegistryDirectory<T> extends AbstractDirectory<T> implements NotifyListener {

    private static final Logger logger = LoggerFactory.getLogger(RegistryDirectory.class);

    private static final Cluster cluster = ExtensionLoader.getExtensionLoader(Cluster.class).getAdaptiveExtension();

    private static final RouterFactory routerFactory = ExtensionLoader.getExtensionLoader(RouterFactory.class).getAdaptiveExtension();

    private static final ConfiguratorFactory configuratorFactory = ExtensionLoader.getExtensionLoader(ConfiguratorFactory.class).getAdaptiveExtension();
    private final String serviceKey; // 构造时初始化，断言不为null
    private final Class<T> serviceType; // 构造时初始化，断言不为null
    private final Map<String, String> queryMap; // 构造时初始化，断言不为null
    private final URL directoryUrl; // 构造时初始化，断言不为null，并且总是赋非null值
    private final String[] serviceMethods;
    private final boolean multiGroup;
    private Protocol protocol; // 注入时初始化，断言不为null
    private Registry registry; // 注入时初始化，断言不为null
    private volatile boolean forbidden = false;

    private volatile URL overrideDirectoryUrl; // 构造时初始化，断言不为null，并且总是赋非null值

    /*override规则 
     * 优先级：override>-D>consumer>provider
     * 第一种规则：针对某个provider <ip:port,timeout=100>
     * 第二种规则：针对所有provider <* ,timeout=5000>
     */
    private volatile List<Configurator> configurators; // 初始为null以及中途可能被赋为null，请使用局部变量引用

    // Map<url, Invoker> cache service url to invoker mapping.
    private volatile Map<String, Invoker<T>> urlInvokerMap; // 初始为null以及中途可能被赋为null，请使用局部变量引用

    // Map<methodName, Invoker> cache service method to invokers mapping.
    private volatile Map<String, List<Invoker<T>>> methodInvokerMap; // 初始为null以及中途可能被赋为null，请使用局部变量引用

    // Set<invokerUrls> cache invokeUrls to invokers mapping.
    private volatile Set<URL> cachedInvokerUrls; // 初始为null以及中途可能被赋为null，请使用局部变量引用

    public RegistryDirectory(Class<T> serviceType, URL url) {
        super(url);
        if (serviceType == null)
            throw new IllegalArgumentException("service type is null.");
        if (url.getServiceKey() == null || url.getServiceKey().length() == 0)
            throw new IllegalArgumentException("registry serviceKey is null.");
        this.serviceType = serviceType;
        this.serviceKey = url.getServiceKey();
        this.queryMap = StringUtils.parseQueryString(url.getParameterAndDecoded(Constants.REFER_KEY));
        this.overrideDirectoryUrl = this.directoryUrl = url.setPath(url.getServiceInterface()).clearParameters().addParameters(queryMap).removeParameter(Constants.MONITOR_KEY);
        String group = directoryUrl.getParameter(Constants.GROUP_KEY, "");
        this.multiGroup = group != null && ("*".equals(group) || group.contains(","));
        String methods = queryMap.get(Constants.METHODS_KEY);
        this.serviceMethods = methods == null ? null : Constants.COMMA_SPLIT_PATTERN.split(methods);
    }

    /**
     * 将overrideURL转换为map，供重新refer时使用.
     * 每次下发全部规则，全部重新组装计算
     *
     * @param urls 契约：
     *             </br>1.override://0.0.0.0/...(或override://ip:port...?anyhost=true)&para1=value1...表示全局规则(对所有的提供者全部生效)
     *             </br>2.override://ip:port...?anyhost=false 特例规则（只针对某个提供者生效）
     *             </br>3.不支持override://规则... 需要注册中心自行计算.
     *             </br>4.不带参数的override://0.0.0.0/ 表示清除override
     * @return
     */
    public static List<Configurator> toConfigurators(List<URL> urls) {
        if (urls == null || urls.size() == 0) {
            return Collections.emptyList();
        }

        List<Configurator> configurators = new ArrayList<Configurator>(urls.size());
        for (URL url : urls) {
            if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                configurators.clear();
                break;
            }
            Map<String, String> override = new HashMap<String, String>(url.getParameters());
            //override 上的anyhost可能是自动添加的，不能影响改变url判断
            override.remove(Constants.ANYHOST_KEY);
            if (override.size() == 0) {
                configurators.clear();
                continue;
            }
            configurators.add(configuratorFactory.getConfigurator(url));
        }
        Collections.sort(configurators);
        return configurators;
    }

    public void setProtocol(Protocol protocol) {
        this.protocol = protocol;
    }

    public void setRegistry(Registry registry) {
        this.registry = registry;
    }

    public void subscribe(URL url) {
        setConsumerUrl(url);
        registry.subscribe(url, this);
    }

    public void destroy() {
        if (isDestroyed()) {
            return;
        }
        // unsubscribe.
        try {
            if (getConsumerUrl() != null && registry != null && registry.isAvailable()) {
                registry.unsubscribe(getConsumerUrl(), this);
            }
        } catch (Throwable t) {
            logger.warn("unexpeced error when unsubscribe service " + serviceKey + "from registry" + registry.getUrl(), t);
        }
        super.destroy(); // 必须在unsubscribe之后执行
        try {
            destroyAllInvokers();
        } catch (Throwable t) {
            logger.warn("Failed to destroy service " + serviceKey, t);
        }
    }

    /**
     * RegistryDirectory 是一个动态服务目录，会随注册中心配置的变化进行动态调整。
     * 因此 RegistryDirectory 实现了 NotifyListener 接口，通过这个接口获取注册中心变更通知。
     *
     * 首先是根据 url 的 category 参数对 url 进行分门别类存储，
     * 然后通过 toRouters 和 toConfigurators 将 url 列表转成 Router 和 Configurator 列表。
     * 最后调用 refreshInvoker 方法刷新 Invoker 列表。
     *
     * @param urls 已注册信息列表，总不为空，含义同{@link com.alibaba.dubbo.registry.RegistryService#lookup(URL)}的返回值。
     */
    public synchronized void notify(List<URL> urls) {
        // 定义三个集合，分别用于存放服务提供者 url，路由 url，配置器 url
        List<URL> invokerUrls = new ArrayList<URL>();
        List<URL> routerUrls = new ArrayList<URL>();
        List<URL> configuratorUrls = new ArrayList<URL>();

        for (URL url : urls) {
            String protocol = url.getProtocol();

            // 获取 category 参数
            String category = url.getParameter(Constants.CATEGORY_KEY, Constants.DEFAULT_CATEGORY);

            // 根据 category 参数将 url 分别放到不同的列表中
            if (Constants.ROUTERS_CATEGORY.equals(category)
                    || Constants.ROUTE_PROTOCOL.equals(protocol)) {
                // 添加路由器 url
                routerUrls.add(url);
            } else if (Constants.CONFIGURATORS_CATEGORY.equals(category)
                    || Constants.OVERRIDE_PROTOCOL.equals(protocol)) {
                // 添加配置器 url
                configuratorUrls.add(url);
            } else if (Constants.PROVIDERS_CATEGORY.equals(category)) {
                // 添加服务提供者 url
                invokerUrls.add(url);
            } else {
                // 忽略不支持的 category
                logger.warn("Unsupported category " + category + " in notified url: " + url + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost());
            }
        }

        // configurators
        if (configuratorUrls != null && configuratorUrls.size() > 0) {
            // 将 url 转成 Configurator
            this.configurators = toConfigurators(configuratorUrls);
        }

        // routers
        if (routerUrls != null && routerUrls.size() > 0) {
            // 将 url 转成 Router
            List<Router> routers = toRouters(routerUrls);
            if (routers != null) { // null - do nothing
                setRouters(routers);
            }
        }

        List<Configurator> localConfigurators = this.configurators; // local reference
        // 合并override参数
        this.overrideDirectoryUrl = directoryUrl;
        if (localConfigurators != null && localConfigurators.size() > 0) {
            for (Configurator configurator : localConfigurators) {
                // 配置 overrideDirectoryUrl
                this.overrideDirectoryUrl = configurator.configure(overrideDirectoryUrl);
            }
        }

        // 刷新 Invoker 列表
        // providers
        refreshInvoker(invokerUrls);
    }

    /**
     * refreshInvoker 方法首先会根据入参 invokerUrls 的数量和协议头判断是否禁用所有的服务，
     * 如果禁用，则将 forbidden 设为 true，并销毁所有的 Invoker。
     * 若不禁用，则将 url 转成 Invoker，得到 <url, Invoker> 的映射关系。
     * 然后进一步进行转换，得到 <methodName, Invoker 列表> 映射关系。
     * 之后进行多组 Invoker 合并操作，并将合并结果赋值给 methodInvokerMap。
     * methodInvokerMap 变量在 doList 方法中会被用到，doList 会对该变量进行读操作，在这里是写操作。
     * 当新的 Invoker 列表生成后，还要一个重要的工作要做，就是销毁无用的 Invoker， 避免服务消费者调用已下线的服务的服务。
     *
     *
     * 到此关于 Invoker 列表的刷新逻辑就分析了，这里对整个过程进行简单总结。如下：
     * 1.检测入参是否仅包含一个 url，且 url 协议头为 empty
     * 2.若第一步检测结果为 true，表示禁用所有服务，此时销毁所有的 Invoker
     * 3.若第一步检测结果为 false，此时将入参转为 Invoker 列表
     * 4.对上一步逻辑生成的结果进行进一步处理，得到方法名到 Invoker 的映射关系表
     * 5.合并多组 Invoker
     * 6.销毁无用 Invoker
     *
     * 根据invokerURL列表转换为invoker列表。转换规则如下：
     * 1.如果url已经被转换为invoker，则不在重新引用，直接从缓存中获取，注意如果url中任何一个参数变更也会重新引用
     * 2.如果传入的invoker列表不为空，则表示最新的invoker列表
     * 3.如果传入的invokerUrl列表是空，则表示只是下发的override规则或route规则，需要重新交叉对比，决定是否需要重新引用。
     *
     * refreshInvoker 方法是保证 RegistryDirectory 随注册中心变化而变化的关键所在。
     *
     * @param invokerUrls 传入的参数不能为null
     */
    // TODO: 2017/8/31 FIXME 使用线程池去刷新地址，否则可能会导致任务堆积
    private void refreshInvoker(List<URL> invokerUrls) {
        // invokerUrls 仅有一个元素，且 url 协议头为 empty，此时表示禁用所有服务
        if (invokerUrls != null && invokerUrls.size() == 1 && invokerUrls.get(0) != null
                && Constants.EMPTY_PROTOCOL.equals(invokerUrls.get(0).getProtocol())) {
            // 设置 forbidden 为 true
            this.forbidden = true; // 禁止访问
            this.methodInvokerMap = null; // 置空列表
            // 销毁所有 Invoker
            destroyAllInvokers(); // 关闭所有Invoker
        } else {
            this.forbidden = false; // 允许访问
            Map<String, Invoker<T>> oldUrlInvokerMap = this.urlInvokerMap; // local reference
            if (invokerUrls.size() == 0 && this.cachedInvokerUrls != null) {
                // 添加缓存 url 到 invokerUrls 中
                invokerUrls.addAll(this.cachedInvokerUrls);
            } else {
                this.cachedInvokerUrls = new HashSet<URL>();
                // 缓存 invokerUrls
                this.cachedInvokerUrls.addAll(invokerUrls);//缓存invokerUrls列表，便于交叉对比
            }
            if (invokerUrls.size() == 0) {
                return;
            }

            // 将 url 转成 Invoker
            Map<String, Invoker<T>> newUrlInvokerMap = toInvokers(invokerUrls);// 将URL列表转成Invoker列表

            // 将 newUrlInvokerMap 转成方法名到 Invoker 列表的映射
            Map<String, List<Invoker<T>>> newMethodInvokerMap = toMethodInvokers(newUrlInvokerMap); // 换方法名映射Invoker列表

            // state change
            //如果计算错误，则不进行处理.
            // 转换出错，直接打印异常，并返回
            if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
                logger.error(new IllegalStateException("urls to invokers error .invokerUrls.size :" + invokerUrls.size() + ", invoker.size :0. urls :" + invokerUrls.toString()));
                return;
            }

            // 合并多个组的 Invoker
            this.methodInvokerMap = multiGroup ? toMergeMethodInvokerMap(newMethodInvokerMap) : newMethodInvokerMap;
            this.urlInvokerMap = newUrlInvokerMap;

            try {
                // 销毁无用 Invoker
                destroyUnusedInvokers(oldUrlInvokerMap, newUrlInvokerMap); // 关闭未使用的Invoker
            } catch (Exception e) {
                logger.warn("destroyUnusedInvokers error. ", e);
            }
        }
    }

    /**
     * 多组服务的合并逻辑。
     *
     * 首先是生成 group 到 Invoker 列表的映射关系表，若关系表中的映射关系数量大于1，表示有多组服务。
     * 此时通过集群类合并每组 Invoker，并将合并结果存储到 groupInvokers 中。
     * 之后将方法名与 groupInvokers 存到到 result 中，并返回，整个逻辑结束。
     *
     * @param methodMap
     * @return
     */
    private Map<String, List<Invoker<T>>> toMergeMethodInvokerMap(Map<String, List<Invoker<T>>> methodMap) {
        Map<String, List<Invoker<T>>> result = new HashMap<String, List<Invoker<T>>>();

        // 遍历入参
        for (Map.Entry<String, List<Invoker<T>>> entry : methodMap.entrySet()) {
            String method = entry.getKey();
            List<Invoker<T>> invokers = entry.getValue();

            // group -> Invoker 列表
            Map<String, List<Invoker<T>>> groupMap = new HashMap<String, List<Invoker<T>>>();

            // 遍历 Invoker 列表
            for (Invoker<T> invoker : invokers) {
                // 获取分组配置
                String group = invoker.getUrl().getParameter(Constants.GROUP_KEY, "");
                List<Invoker<T>> groupInvokers = groupMap.get(group);

                if (groupInvokers == null) {
                    groupInvokers = new ArrayList<Invoker<T>>();
                    // 缓存 <group, List<Invoker>> 到 groupMap 中
                    groupMap.put(group, groupInvokers);
                }

                // 存储 invoker 到 groupInvokers
                groupInvokers.add(invoker);
            }

            if (groupMap.size() == 1) {
                // 如果 groupMap 中仅包含一组键值对，此时直接取出该键值对的值即可
                result.put(method, groupMap.values().iterator().next());
            } else if (groupMap.size() > 1) {
                // groupMap.size() > 1 成立，表示 groupMap 中包含多组键值对，比如：
                // {
                //     "dubbo": [invoker1, invoker2, invoker3, ...],
                //     "hello": [invoker4, invoker5, invoker6, ...]
                // }
                List<Invoker<T>> groupInvokers = new ArrayList<Invoker<T>>();

                for (List<Invoker<T>> groupList : groupMap.values()) {
                    // 通过集群类合并每个分组对应的 Invoker 列表
                    groupInvokers.add(cluster.join(new StaticDirectory<T>(groupList)));
                }

                // 缓存结果
                result.put(method, groupInvokers);
            } else {
                result.put(method, invokers);
            }
        }
        return result;
    }

    /**
     * @param urls
     * @return null : no routers ,do nothing
     * else :routers list
     */
    private List<Router> toRouters(List<URL> urls) {
        List<Router> routers = new ArrayList<Router>();
        if (urls == null || urls.size() < 1) {
            return routers;
        }
        if (urls != null && urls.size() > 0) {
            for (URL url : urls) {
                if (Constants.EMPTY_PROTOCOL.equals(url.getProtocol())) {
                    continue;
                }
                String routerType = url.getParameter(Constants.ROUTER_KEY);
                if (routerType != null && routerType.length() > 0) {
                    url = url.setProtocol(routerType);
                }
                try {
                    Router router = routerFactory.getRouter(url);
                    if (!routers.contains(router))
                        routers.add(router);
                } catch (Throwable t) {
                    logger.error("convert router url to router error, url: " + url, t);
                }
            }
        }
        return routers;
    }

    /**
     * 将urls转成invokers,如果url已经被refer过，不再重新引用。
     *
     * 一开始会对服务提供者 url 进行检测，
     * 若服务消费端的配置不支持服务端的协议，或服务端 url 协议头为 empty 时，toInvokers 均会忽略服务提供方 url。
     * 必要的检测做完后，紧接着是合并 url，然后访问缓存，尝试获取与 url 对应的 invoker。
     * 如果缓存命中，直接将 Invoker 存入 newUrlInvokerMap 中即可。如果未命中，则需新建 Invoker。
     *
     * @param urls
     * @param overrides
     * @param query
     * @return invokers
     */
    private Map<String, Invoker<T>> toInvokers(List<URL> urls) {
        Map<String, Invoker<T>> newUrlInvokerMap = new HashMap<String, Invoker<T>>();
        if (urls == null || urls.size() == 0) {
            return newUrlInvokerMap;
        }
        Set<String> keys = new HashSet<String>();

        // 获取服务消费端配置的协议
        String queryProtocols = this.queryMap.get(Constants.PROTOCOL_KEY);

        for (URL providerUrl : urls) {
            //如果reference端配置了protocol，则只选择匹配的protocol
            if (queryProtocols != null && queryProtocols.length() > 0) {
                boolean accept = false;

                // 检测服务提供者协议是否被服务消费者所支持
                String[] acceptProtocols = queryProtocols.split(",");
                for (String acceptProtocol : acceptProtocols) {
                    if (providerUrl.getProtocol().equals(acceptProtocol)) {
                        accept = true;
                        break;
                    }
                }
                if (!accept) {
                    // 若服务消费者协议头不被消费者所支持，则忽略当前 providerUrl
                    continue;
                }
            }

            // 忽略 empty 协议
            if (Constants.EMPTY_PROTOCOL.equals(providerUrl.getProtocol())) {
                continue;
            }

            // 通过 SPI 检测服务端协议是否被消费端支持，不支持则抛出异常
            if (!ExtensionLoader.getExtensionLoader(Protocol.class).hasExtension(providerUrl.getProtocol())) {
                logger.error(new IllegalStateException("Unsupported protocol " + providerUrl.getProtocol() + " in notified url: " + providerUrl + " from registry " + getUrl().getAddress() + " to consumer " + NetUtils.getLocalHost()
                        + ", supported protocol: " + ExtensionLoader.getExtensionLoader(Protocol.class).getSupportedExtensions()));
                continue;
            }

            // 合并 url
            URL url = mergeUrl(providerUrl);

            String key = url.toFullString(); // URL参数是排序的
            if (keys.contains(key)) { // 重复URL
                // 忽略重复 url
                continue;
            }
            keys.add(key);

            // 缓存key为没有合并消费端参数的URL，不管消费端如何合并参数，如果服务端URL发生变化，则重新refer
            // 将本地 Invoker 缓存赋值给 localUrlInvokerMap
            Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference

            // 获取与 url 对应的 Invoker
            Invoker<T> invoker = localUrlInvokerMap == null ? null : localUrlInvokerMap.get(key);

            // 缓存未命中
            if (invoker == null) { // 缓存中没有，重新refer
                try {
                    boolean enabled = true;
                    if (url.hasParameter(Constants.DISABLED_KEY)) {
                        // 获取 disable 配置，取反，然后赋值给 enable 变量
                        enabled = !url.getParameter(Constants.DISABLED_KEY, false);
                    } else {
                        // 获取 enable 配置，并赋值给 enable 变量
                        enabled = url.getParameter(Constants.ENABLED_KEY, true);
                    }
                    if (enabled) {
                        // 调用 refer 获取 Invoker -> DubboInvoker -> 内部持有一个 ExchangeClient
                        invoker = new InvokerDelegete<T>(protocol.refer(serviceType, url), url, providerUrl);
                    }
                } catch (Throwable t) {
                    logger.error("Failed to refer invoker for interface:" + serviceType + ",url:(" + url + ")" + t.getMessage(), t);
                }

                if (invoker != null) { // 将新的引用放入缓存
                    // 缓存 Invoker 实例
                    newUrlInvokerMap.put(key, invoker);
                }
            } else {
                // 缓存命中
                // 将 invoker 存储到 newUrlInvokerMap 中
                newUrlInvokerMap.put(key, invoker);
            }
        }

        keys.clear();
        return newUrlInvokerMap;
    }

    /**
     * 合并url参数 顺序为override > -D >Consumer > Provider
     *
     * @param providerUrl
     * @param overrides
     * @return
     */
    private URL mergeUrl(URL providerUrl) {
        providerUrl = ClusterUtils.mergeUrl(providerUrl, queryMap); // 合并消费端参数

        List<Configurator> localConfigurators = this.configurators; // local reference
        if (localConfigurators != null && localConfigurators.size() > 0) {
            for (Configurator configurator : localConfigurators) {
                providerUrl = configurator.configure(providerUrl);
            }
        }

        providerUrl = providerUrl.addParameter(Constants.CHECK_KEY, String.valueOf(false)); // 不检查连接是否成功，总是创建Invoker！

        //directoryUrl 与 override 合并是在notify的最后，这里不能够处理
        this.overrideDirectoryUrl = this.overrideDirectoryUrl.addParametersIfAbsent(providerUrl.getParameters()); // 合并提供者参数        

        if ((providerUrl.getPath() == null || providerUrl.getPath().length() == 0)
                && "dubbo".equals(providerUrl.getProtocol())) { // 兼容1.0
            //fix by tony.chenl DUBBO-44
            String path = directoryUrl.getParameter(Constants.INTERFACE_KEY);
            if (path != null) {
                int i = path.indexOf('/');
                if (i >= 0) {
                    path = path.substring(i + 1);
                }
                i = path.lastIndexOf(':');
                if (i >= 0) {
                    path = path.substring(0, i);
                }
                providerUrl = providerUrl.setPath(path);
            }
        }
        return providerUrl;
    }

    private List<Invoker<T>> route(List<Invoker<T>> invokers, String method) {
        Invocation invocation = new RpcInvocation(method, new Class<?>[0], new Object[0]);
        List<Router> routers = getRouters();
        if (routers != null) {
            for (Router router : routers) {
                if (router.getUrl() != null && !router.getUrl().getParameter(Constants.RUNTIME_KEY, true)) {
                    invokers = router.route(invokers, getConsumerUrl(), invocation);
                }
            }
        }
        return invokers;
    }

    /**
     * 将invokers列表转成与方法的映射关系
     *
     * 主要做了三件事情，
     * 第一是对入参进行遍历，然后从 Invoker 的 url 成员变量中获取 methods 参数，并切分成数组。
     * 随后以方法名为键，Invoker 列表为值，将映射关系存储到 newMethodInvokerMap 中。
     * 第二是分别基于类和方法对 Invoker 列表进行路由操作。
     * 第三是对 Invoker 列表进行排序，并转成不可变列表。
     *
     * @param invokersMap Invoker列表
     * @return Invoker与方法的映射关系
     */
    private Map<String, List<Invoker<T>>> toMethodInvokers(Map<String, Invoker<T>> invokersMap) {
        // 方法名 -> Invoker 列表
        Map<String, List<Invoker<T>>> newMethodInvokerMap = new HashMap<String, List<Invoker<T>>>();

        // 按提供者URL所声明的methods分类，兼容注册中心执行路由过滤掉的methods
        List<Invoker<T>> invokersList = new ArrayList<Invoker<T>>();

        if (invokersMap != null && invokersMap.size() > 0) {
            for (Invoker<T> invoker : invokersMap.values()) {
                // 获取 methods 参数
                String parameter = invoker.getUrl().getParameter(Constants.METHODS_KEY);

                if (parameter != null && parameter.length() > 0) {
                    // 切分 methods 参数值，得到方法名数组
                    String[] methods = Constants.COMMA_SPLIT_PATTERN.split(parameter);

                    if (methods != null && methods.length > 0) {

                        for (String method : methods) {
                            // 方法名不为 *
                            if (method != null && method.length() > 0
                                    && !Constants.ANY_VALUE.equals(method)) {

                                // 根据方法名获取 Invoker 列表
                                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                                if (methodInvokers == null) {
                                    methodInvokers = new ArrayList<Invoker<T>>();
                                    newMethodInvokerMap.put(method, methodInvokers);
                                }

                                // 存储 Invoker 到列表中
                                methodInvokers.add(invoker);
                            }
                        }
                    }
                }

                invokersList.add(invoker);
            }
        }

        // 进行服务级别路由，参考 pull request #749
        List<Invoker<T>> newInvokersList = route(invokersList, null);

        // 存储 <*, newInvokersList> 映射关系
        newMethodInvokerMap.put(Constants.ANY_VALUE, newInvokersList);

        if (serviceMethods != null && serviceMethods.length > 0) {
            for (String method : serviceMethods) {
                List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
                if (methodInvokers == null || methodInvokers.size() == 0) {
                    methodInvokers = newInvokersList;
                }
                // 进行方法级别路由
                newMethodInvokerMap.put(method, route(methodInvokers, method));
            }
        }

        // sort and unmodifiable
        // 排序，转成不可变列表
        for (String method : new HashSet<String>(newMethodInvokerMap.keySet())) {
            List<Invoker<T>> methodInvokers = newMethodInvokerMap.get(method);
            Collections.sort(methodInvokers, InvokerComparator.getComparator());
            newMethodInvokerMap.put(method, Collections.unmodifiableList(methodInvokers));
        }
        return Collections.unmodifiableMap(newMethodInvokerMap);
    }

    /**
     * 关闭所有Invoker
     */
    private void destroyAllInvokers() {
        Map<String, Invoker<T>> localUrlInvokerMap = this.urlInvokerMap; // local reference
        if (localUrlInvokerMap != null) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                try {
                    invoker.destroy();
                } catch (Throwable t) {
                    logger.warn("Failed to destroy service " + serviceKey + " to provider " + invoker.getUrl(), t);
                }
            }
            localUrlInvokerMap.clear();
        }
        methodInvokerMap = null;
    }

    /**
     * 删除无用 Invoker
     *
     * destroyUnusedInvokers 方法的主要逻辑是通过 newUrlInvokerMap 找出待删除 Invoker 对应的 url，
     * 并将 url 存入到 deleted 列表中。
     * 然后再遍历 deleted 列表，并从 oldUrlInvokerMap 中移除相应的 Invoker，销毁之。
     *
     * 检查缓存中的invoker是否需要被destroy
     * 如果url中指定refer.autodestroy=false，则只增加不减少，可能会有refer泄漏，
     *
     * @param
     */
    private void destroyUnusedInvokers(Map<String, Invoker<T>> oldUrlInvokerMap, Map<String, Invoker<T>> newUrlInvokerMap) {
        if (newUrlInvokerMap == null || newUrlInvokerMap.size() == 0) {
            destroyAllInvokers();
            return;
        }
        // check deleted invoker
        List<String> deleted = null;
        if (oldUrlInvokerMap != null) {
            // 获取新生成的 Invoker 列表
            Collection<Invoker<T>> newInvokers = newUrlInvokerMap.values();

            // 遍历老的 <url, Invoker> 映射表
            for (Map.Entry<String, Invoker<T>> entry : oldUrlInvokerMap.entrySet()) {
                // 检测 newInvokers 中是否包含老的 Invoker
                if (!newInvokers.contains(entry.getValue())) {

                    if (deleted == null) {
                        deleted = new ArrayList<String>();
                    }

                    // 若不包含，则将老的 Invoker 对应的 url 存入 deleted 列表中
                    deleted.add(entry.getKey());
                }
            }
        }

        if (deleted != null) {
            // 遍历 deleted 集合，并到老的 <url, Invoker> 映射关系表查出 Invoker，销毁之
            for (String url : deleted) {
                if (url != null) {
                    // 从 oldUrlInvokerMap 中移除 url 对应的 Invoker
                    Invoker<T> invoker = oldUrlInvokerMap.remove(url);
                    if (invoker != null) {
                        try {
                            invoker.destroy();
                            if (logger.isDebugEnabled()) {
                                logger.debug("destory invoker[" + invoker.getUrl() + "] success. ");
                            }
                        } catch (Exception e) {
                            logger.warn("destory invoker[" + invoker.getUrl() + "] faild. " + e.getMessage(), e);
                        }
                    }
                }
            }
        }
    }

    /**
     * 从 localMethodInvokerMap 中获取到 Invoker 列表。
     * 一般情况下，普通的调用可通过方法名获取到对应的 Invoker 列表，泛化调用可通过 ***** 获取到 Invoker 列表。
     * localMethodInvokerMap 源自 RegistryDirectory 类的成员变量 methodInvokerMap。
     * doList 方法可以看做是对 methodInvokerMap 变量的读操作
     *
     * @param invocation
     * @return
     */
    public List<Invoker<T>> doList(Invocation invocation) {
        if (forbidden) {
            // 1. 没有服务提供者 2. 服务提供者被禁用
            // 服务提供者关闭或禁用了服务，此时抛出 No provider 异常
            throw new RpcException(RpcException.FORBIDDEN_EXCEPTION,
                "No provider available from registry " + getUrl().getAddress() + " for service " + getConsumerUrl().getServiceKey() + " on consumer " +  NetUtils.getLocalHost()
                    + " use dubbo version " + Version.getVersion() + ", may be providers disabled or not registered ?");
        }

        List<Invoker<T>> invokers = null;

        // 获取 Invoker 本地缓存
        Map<String, List<Invoker<T>>> localMethodInvokerMap = this.methodInvokerMap; // local reference

        if (localMethodInvokerMap != null && localMethodInvokerMap.size() > 0) {
            // 获取方法名和参数列表
            String methodName = RpcUtils.getMethodName(invocation);
            Object[] args = RpcUtils.getArguments(invocation);

            // 检测参数列表的第一个参数是否为 String 或 enum 类型
            if (args != null && args.length > 0 && args[0] != null
                    && (args[0] instanceof String || args[0].getClass().isEnum())) {
                // 通过 方法名 + 第一个参数名称 查询 Invoker 列表，具体的使用场景暂时没想到
                invokers = localMethodInvokerMap.get(methodName + "." + args[0]); // 可根据第一个参数枚举路由
            }

            if (invokers == null) {
                // 通过方法名获取 Invoker 列表
                invokers = localMethodInvokerMap.get(methodName);
            }

            if (invokers == null) {
                // 通过星号 * 获取 Invoker 列表
                invokers = localMethodInvokerMap.get(Constants.ANY_VALUE);
            }

            // 冗余逻辑，pull request #2861 移除了下面的 if 分支代码
            if (invokers == null) {
                Iterator<List<Invoker<T>>> iterator = localMethodInvokerMap.values().iterator();
                if (iterator.hasNext()) {
                    invokers = iterator.next();
                }
            }
        }

        // 返回 Invoker 列表
        return invokers == null ? new ArrayList<Invoker<T>>(0) : invokers;
    }

    public Class<T> getInterface() {
        return serviceType;
    }

    public URL getUrl() {
        return this.overrideDirectoryUrl;
    }

    public boolean isAvailable() {
        if (isDestroyed()) {
            return false;
        }
        Map<String, Invoker<T>> localUrlInvokerMap = urlInvokerMap;
        if (localUrlInvokerMap != null && localUrlInvokerMap.size() > 0) {
            for (Invoker<T> invoker : new ArrayList<Invoker<T>>(localUrlInvokerMap.values())) {
                if (invoker.isAvailable()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, Invoker<T>> getUrlInvokerMap() {
        return urlInvokerMap;
    }

    /**
     * Haomin: added for test purpose
     */
    public Map<String, List<Invoker<T>>> getMethodInvokerMap() {
        return methodInvokerMap;
    }

    private static class InvokerComparator implements Comparator<Invoker<?>> {

        private static final InvokerComparator comparator = new InvokerComparator();

        private InvokerComparator() {
        }

        public static InvokerComparator getComparator() {
            return comparator;
        }

        public int compare(Invoker<?> o1, Invoker<?> o2) {
            return o1.getUrl().toString().compareTo(o2.getUrl().toString());
        }

    }

    /**
     * 代理类，主要用于存储注册中心下发的url地址，用于重新重新refer时能够根据providerURL queryMap overrideMap重新组装
     *
     * @param <T>
     * @author chao.liuc
     */
    private static class InvokerDelegete<T> extends InvokerWrapper<T> {
        private URL providerUrl;

        public InvokerDelegete(Invoker<T> invoker, URL url, URL providerUrl) {
            super(invoker, url);
            this.providerUrl = providerUrl;
        }

        public URL getProviderUrl() {
            return providerUrl;
        }
    }
}
