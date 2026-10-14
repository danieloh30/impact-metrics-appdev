import {readFileSync,writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';

export function secureRoute(manifest,namespace,owner,proxyImage) {
 if(!/^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(namespace)||!owner||owner.length>200||!proxyImage)throw new Error('Namespace, workspace owner and OAuth proxy image are required');
 const source=Array.isArray(manifest)?manifest:manifest.items??[manifest];
 const managed=new Set(['ServiceAccount:impact-hours-route','Secret:impact-hours-route-token','ConfigMap:impact-hours-access','Role:impact-hours-access','RoleBinding:impact-hours-access']);
 const items=structuredClone(source.filter(item=>!managed.has(item.kind+':'+item.metadata.name)));
 const app=items.find(item=>item.kind==='StatefulSet'&&item.metadata.name==='impact-hours');
 const service=items.find(item=>item.kind==='Service'&&item.metadata.name==='impact-hours');
 const route=items.find(item=>item.kind==='Route'&&item.metadata.name==='impact-hours');
 if(!app||!service||!route)throw new Error('Quarkus must generate the workspace StatefulSet, Service and Route');
 const labels={'app.kubernetes.io/name':'impact-hours'};
 const metadata=name=>({name,namespace,labels});
 app.spec.template.spec.serviceAccountName='impact-hours-route';
 // The app does not use Kubernetes credentials. Only the OAuth proxy receives them.
 app.spec.template.spec.containers=app.spec.template.spec.containers.filter(container=>container.name!=='oauth-proxy');
 app.spec.template.spec.volumes=(app.spec.template.spec.volumes??[]).filter(volume=>!volume.name.startsWith('oauth-'));
 const container=app.spec.template.spec.containers.find(container=>container.name==='impact-hours');
 container.env=(container.env??[]).filter(item=>item.name!=='QUARKUS_HTTP_HOST');container.env.push({name:'QUARKUS_HTTP_HOST',value:'127.0.0.1'});
 // NetworkPolicies are additive; Sandbox-wide allow policies can admit other
 // pods. Bind the app to pod loopback so they cannot bypass the OAuth proxy.
 for(const name of ['startupProbe','readinessProbe','livenessProbe']) {
  // Explicit nulls also remove old, unmanaged handlers when migrating a
  // StatefulSet previously created by Fabric8 rather than oc apply.
  const original=container[name]??{};original.httpGet=null;original.tcpSocket=null;original.grpc=null;
  original.exec={command:['curl','--fail','--silent','--show-error','--output','/dev/null','http://127.0.0.1:8097'+(name==='livenessProbe'?'/api/config':'/api/runs')]};
  container[name]=original;
 }
 container.volumeMounts??=[];
 app.spec.template.spec.automountServiceAccountToken=false;
 // OAuth proxy reads its OAuth client secret at startup. A server-issued service
 // account token Secret avoids caching an expired projected client secret.
 app.spec.template.spec.volumes.push({name:'oauth-service-account',secret:{secretName:'impact-hours-route-token'}});
 app.spec.template.spec.volumes.push({name:'oauth-tls',secret:{secretName:'impact-hours-route-tls'}},{name:'oauth-cookie',secret:{secretName:'impact-hours-route-cookie'}});
 const probe={httpGet:{path:'/oauth/healthz',port:8443,scheme:'HTTPS'},initialDelaySeconds:5,periodSeconds:10};
 app.spec.template.spec.containers.push({name:'oauth-proxy',image:proxyImage,imagePullPolicy:'IfNotPresent',ports:[{name:'oauth',containerPort:8443}],args:[
  '--https-address=:8443','--http-address=','--provider=openshift','--openshift-service-account=impact-hours-route',
  '--upstream=http://127.0.0.1:8097','--upstream-timeout=180s','--email-domain=*',
  '--openshift-sar='+JSON.stringify({namespace,resource:'configmaps',resourceName:'impact-hours-access',verb:'get'}),
  '--tls-cert=/etc/oauth/tls/tls.crt','--tls-key=/etc/oauth/tls/tls.key','--cookie-secret-file=/etc/oauth/cookie/cookie-secret',
  '--cookie-secure=true','--cookie-samesite=lax','--pass-access-token=false','--pass-basic-auth=false','--pass-user-headers=false','--request-logging=false',
  '--skip-provider-button=true'],
  volumeMounts:[{name:'oauth-tls',mountPath:'/etc/oauth/tls',readOnly:true},{name:'oauth-cookie',mountPath:'/etc/oauth/cookie',readOnly:true},{name:'oauth-service-account',mountPath:'/var/run/secrets/kubernetes.io/serviceaccount',readOnly:true}],
  securityContext:{runAsNonRoot:true,allowPrivilegeEscalation:false,readOnlyRootFilesystem:true,capabilities:{drop:['ALL']}},resources:{requests:{cpu:'50m',memory:'64Mi'},limits:{cpu:'250m',memory:'256Mi'}},readinessProbe:probe,livenessProbe:probe});
 service.metadata.annotations??={};service.metadata.annotations['service.beta.openshift.io/serving-cert-secret-name']='impact-hours-route-tls';
 service.spec.ports=[{name:'oauth',port:443,targetPort:'oauth',protocol:'TCP'}];
 route.spec.port={targetPort:'oauth'};route.spec.tls={termination:'reencrypt',insecureEdgeTerminationPolicy:'Redirect'};
 route.metadata.annotations??={};route.metadata.annotations['haproxy.router.openshift.io/timeout']='180s';
 items.push({apiVersion:'v1',kind:'ServiceAccount',metadata:{...metadata('impact-hours-route'),annotations:{'serviceaccounts.openshift.io/oauth-redirectreference.primary':JSON.stringify({kind:'OAuthRedirectReference',apiVersion:'v1',reference:{kind:'Route',name:'impact-hours'}})}}},
  {apiVersion:'v1',kind:'Secret',type:'kubernetes.io/service-account-token',metadata:{...metadata('impact-hours-route-token'),annotations:{'kubernetes.io/service-account.name':'impact-hours-route'}}},
  {apiVersion:'v1',kind:'ConfigMap',metadata:metadata('impact-hours-access'),data:{purpose:'Permission marker for the private Impact Hours workspace'}},
  {apiVersion:'rbac.authorization.k8s.io/v1',kind:'Role',metadata:metadata('impact-hours-access'),rules:[{apiGroups:[''],resources:['configmaps'],resourceNames:['impact-hours-access'],verbs:['get']}]},
  {apiVersion:'rbac.authorization.k8s.io/v1',kind:'RoleBinding',metadata:metadata('impact-hours-access'),roleRef:{apiGroup:'rbac.authorization.k8s.io',kind:'Role',name:'impact-hours-access'},subjects:[{kind:'User',apiGroup:'rbac.authorization.k8s.io',name:owner}]});
 return {apiVersion:'v1',kind:'List',items};
}
if(process.argv[1]===fileURLToPath(import.meta.url)) {
 const [file,namespace,owner,proxyImage]=process.argv.slice(2);
 const result=JSON.stringify(secureRoute(JSON.parse(readFileSync(file,'utf8')),namespace,owner,proxyImage),null,2)+'\n';
 writeFileSync(file,result);writeFileSync(file.replace(/\.json$/,'.yml'),result);
}
