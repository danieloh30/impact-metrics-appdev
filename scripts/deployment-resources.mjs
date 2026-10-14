import {readFileSync} from 'node:fs';

// Keep the generated Quarkus build resources authoritative. Deploy the stateful
// workspace only after its exact image is available in the project registry.
export function resources(manifest, stage, image) {
 const items=Array.isArray(manifest)?manifest:manifest.items??[manifest];
 const app=items.find(item=>item.kind==='StatefulSet'&&item.metadata.name==='impact-hours');
 const build=items.find(item=>item.kind==='BuildConfig'&&item.metadata.name==='impact-hours');
 if(!app||!build)throw new Error('Expected impact-hours StatefulSet and BuildConfig');
 if(stage==='tag') {
  const tag=build.spec.output.to;
  if(tag.kind!=='ImageStreamTag'||!/^impact-hours:[\w.-]+$/.test(tag.name))throw new Error('Invalid build output ImageStreamTag');
  return tag.name;
 }
 if(stage==='prerequisites')return {apiVersion:'v1',kind:'List',items:items.filter(item=>item.kind!=='StatefulSet')};
 if(stage!=='workload'||!/^image-registry\.openshift-image-registry\.svc:5000\/[a-z0-9-]+\/impact-hours@sha256:[a-f0-9]{64}$/.test(image??''))throw new Error('An immutable internal-registry image digest is required');
 const workload=structuredClone(app);
 workload.spec.template.spec.containers.find(container=>container.name==='impact-hours').image=image;
 // ImageStream triggers could replace the pinned image with a mutable tag later.
 delete workload.metadata.annotations?.['image.openshift.io/triggers'];
 return {apiVersion:'v1',kind:'List',items:[workload]};
}

if(process.argv[1]?.endsWith('/deployment-resources.mjs')) {
 const [file,stage,image]=process.argv.slice(2);
 const result=resources(JSON.parse(readFileSync(file,'utf8')),stage,image);
 console.log(typeof result==='string'?result:JSON.stringify(result));
}
