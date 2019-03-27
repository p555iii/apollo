package com.ctrip.framework.apollo.portal.service;

import com.ctrip.framework.apollo.common.dto.ClusterDTO;
import com.ctrip.framework.apollo.common.exception.BadRequestException;
import com.ctrip.framework.apollo.core.enums.Env;
import com.ctrip.framework.apollo.portal.api.AdminServiceAPI;
import com.ctrip.framework.apollo.portal.constant.TracerEventType;
import com.ctrip.framework.apollo.portal.spi.UserInfoHolder;
import com.ctrip.framework.apollo.tracer.Tracer;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClusterService {

  private final UserInfoHolder userInfoHolder;
  private final AdminServiceAPI.ClusterAPI clusterAPI;

  public ClusterService(final UserInfoHolder userInfoHolder, final AdminServiceAPI.ClusterAPI clusterAPI) {
    this.userInfoHolder = userInfoHolder;
    this.clusterAPI = clusterAPI;
  }

  public List<ClusterDTO> findClusters(Env env, String appId) {
    return clusterAPI.findClustersByApp(appId, env);
  }

  public ClusterDTO createCluster(Env env, ClusterDTO cluster) {
    // 根据appId 和 环境 和 集群名 到 admin server 去判断  当前集群是否重复
    if (!clusterAPI.isClusterUnique(cluster.getAppId(), env, cluster.getName())) {
      throw new BadRequestException(String.format("cluster %s already exists.", cluster.getName()));
    }
    // 调用 admin server 的rpc 接口 创建集群
    ClusterDTO clusterDTO = clusterAPI.create(env, cluster);

    Tracer.logEvent(TracerEventType.CREATE_CLUSTER, cluster.getAppId(), "0", cluster.getName());

    return clusterDTO;
  }

  public void deleteCluster(Env env, String appId, String clusterName){
    clusterAPI.delete(env, appId, clusterName, userInfoHolder.getUser().getUserId());
  }

  public ClusterDTO loadCluster(String appId, Env env, String clusterName){
    return clusterAPI.loadCluster(appId, env, clusterName);
  }

}
