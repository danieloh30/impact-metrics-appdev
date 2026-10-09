import {test,expect} from '@playwright/test';
for(const phase of [1,4])test(`URL measurement popup prefills original 1000 views and accepts no note in phase ${phase}`,async({page})=>{
 const issue={rowId:phase===1?'c1':'t1',activity:'Part 3: End-to-End Tracing and Observability Across Goose, agentgateway, and Quarkus',url:'https://blog.example.com/post',reason:'No collector for this host; manual collection required',blocking:false,metricType:'VIEWS'};
 const run={id:'measurement-run',createdAt:'2026-10-08T12:00:00Z',status:'AWAITING_APPROVAL',phase,version:4,currentRows:1,trailingRows:phase===4?1:0,trailing:phase===4,review:'Review',error:'',instructionHash:'fixture',issues:[issue],artifacts:{},events:[],approvals:[]};let saved:any=null;
 await page.route('**/api/runs',route=>route.fulfill({json:[run]}));await page.route('**/api/runs/measurement-run',route=>route.fulfill({json:{run,approvalDigest:'digest'}}));await page.route('**/api/runs/measurement-run/events*',route=>route.fulfill({contentType:'text/event-stream',body:''}));
 await page.route('**/api/runs/measurement-run/measurement-input?**',route=>route.fulfill({json:{originalViews:'1000',originalDuration:'2',baseline:'800',views:'1000',duration:'2',value:'1000',canRetain:false}}));
 await page.route('**/api/runs/measurement-run/measurements',route=>{saved=route.request().postDataJSON();return route.fulfill({status:202,json:run});});
 await page.addInitScript(()=>localStorage.setItem('impact-run','measurement-run'));await page.goto('/');await page.getByRole('button',{name:'Enter views',exact:true}).click();const dialog=page.getByRole('dialog',{name:'Enter views',exact:true});
 await expect(dialog.getByLabel('Measured total')).toHaveValue('1000');await expect(dialog).toContainText('Views / attendance: 1000');await expect(dialog.getByLabel('Source / collection note (optional)',{exact:true})).not.toHaveAttribute('required','');await expect(dialog.getByRole('button',{name:'Review original inputs instead'})).toHaveCount(0);
 await dialog.getByRole('button',{name:'Save & refresh review →'}).click();await expect(dialog).toHaveCount(0);expect(saved).toMatchObject({value:'1000',evidence:'',type:'VIEWS'});
});
