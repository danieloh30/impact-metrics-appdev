import {useLayoutEffect,useRef} from 'react';

export type NextStep={key:string;title:string;description:string;sectionId:string;actionId?:string;auto?:boolean;alternate?:{label:string;sectionId:string;actionId:string}};
export const importStart:NextStep={key:'import:connect',title:'Connect SharePoint',description:'Sign in with your IBM account to read both source workbooks.',sectionId:'sharepoint-connection',actionId:'connect-sharepoint'};

function focusTarget(sectionId:string,actionId?:string){
 const section=document.getElementById(sectionId),control=actionId?document.getElementById(actionId):null;
 const focus=control&&!control.matches(':disabled')?control:section;
 if(!focus)return;
 if(!focus.hasAttribute('tabindex')&&!focus.matches('button,a,input,select,textarea'))focus.setAttribute('tabindex','-1');
 focus.focus({preventScroll:true});
}

function moveTo(sectionId:string,actionId?:string,immediate=false){
 const section=document.getElementById(sectionId),control=actionId?document.getElementById(actionId):null;
 if(!section)return;
 const guide=document.querySelector('.workflow-monitor')||document.querySelector('.next-action-guide');
 const gap=(guide?.getBoundingClientRect().height??0)+28;
 const available=window.innerHeight-gap-24;
 const target=control&&(!section.contains(control)||section.getBoundingClientRect().height>available)?control:section;
 const top=target.getBoundingClientRect().top+window.scrollY-gap;
 window.scrollTo({top:Math.max(0,top),behavior:immediate||window.matchMedia('(prefers-reduced-motion: reduce)').matches?'instant':'smooth'});
 focusTarget(sectionId,actionId);
}

export function NextAction({step,paused=false}:{step:NextStep;paused?:boolean}){
 const previous=useRef<string|null>(null),automaticTimer=useRef<number|undefined>(undefined),focusFrame=useRef<number|undefined>(undefined);
 function cancelQueuedMove(){if(automaticTimer.current!==undefined){clearTimeout(automaticTimer.current);automaticTimer.current=undefined;}if(focusFrame.current!==undefined){cancelAnimationFrame(focusFrame.current);focusFrame.current=undefined;}}
 function beginExplicitNavigation(){cancelQueuedMove();window.scrollTo({top:window.scrollY,behavior:'instant'});}
 function goTo(sectionId:string,actionId?:string){
  cancelQueuedMove();moveTo(sectionId,actionId,true);
  // Restore focus after the click's native focus handling/layout, without taking
  // focus back from a field or dialog the user entered in the meantime.
  focusFrame.current=requestAnimationFrame(()=>{focusFrame.current=undefined;const active=document.activeElement;if(active===document.body||!active?.isConnected||document.querySelector('.next-action-guide')?.contains(active))focusTarget(sectionId,actionId);});
 }

 useLayoutEffect(()=>{
  if(paused){cancelQueuedMove();return;}
  const section=document.getElementById(step.sectionId),control=step.actionId?document.getElementById(step.actionId):null;
  section?.classList.add('next-action-section');control?.classList.add('next-action-control');
  const changed=previous.current!==null&&previous.current!==step.key;
  previous.current=step.key;
  const timer=automaticTimer.current=window.setTimeout(()=>{
   automaticTimer.current=undefined;
   const typing=document.activeElement?.matches('textarea,select,input:not([type="checkbox"]):not([type="button"])');
   if(changed&&step.auto!==false&&!typing)moveTo(step.sectionId,step.actionId);
  },0);
  return()=>{clearTimeout(timer);cancelQueuedMove();section?.classList.remove('next-action-section');control?.classList.remove('next-action-control');};
 },[step.key,step.sectionId,step.actionId,step.auto,paused]);
 return <nav className="next-action-guide" aria-label="Next required action" onPointerDownCapture={beginExplicitNavigation} onKeyDownCapture={beginExplicitNavigation}>
  <div role="status" aria-live="polite"><span className="next-action-label">YOUR NEXT STEP</span><strong>{step.title}</strong><p>{step.description}</p></div>
  <div className="next-action-links"><button type="button" disabled={paused} onClick={()=>goTo(step.sectionId,step.actionId)}>Go to section ↓</button>{step.alternate&&<button type="button" className="text-button" disabled={paused} onClick={()=>goTo(step.alternate!.sectionId,step.alternate!.actionId)}>{step.alternate.label}</button>}</div>
 </nav>;
}
