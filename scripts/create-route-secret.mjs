import {randomBytes} from 'node:crypto';
// Output is piped directly to oc apply; never echo secret material to the terminal.
console.log(JSON.stringify({apiVersion:'v1',kind:'Secret',metadata:{name:'impact-hours-route-cookie',labels:{'app.kubernetes.io/name':'impact-hours'}},type:'Opaque',stringData:{'cookie-secret':randomBytes(32).toString('base64')}}));
