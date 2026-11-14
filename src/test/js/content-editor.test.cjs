const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/web/js/core/content-model.js'),'utf8');
const modulePromise=import('data:text/javascript;base64,'+Buffer.from(source).toString('base64'));
function snapshot(){return {revision:'one',catalogs:{contracts:{},stories:{},achievements:{},marks:{},joinquit:{}}};}
test('native contract → achievement → mark and join/quit chain survives serialization',async()=>{
  const {ContentModel,contentLinks,contentErrors}=await modulePromise;const model=new ContentModel(snapshot());
  for(const [kind,id] of [['contracts','order'],['achievements','helper'],['marks','mark'],['joinquit','style']])model.add(kind,id);
  model.connect('contracts:order','achievements:helper');model.connect('achievements:helper','marks:mark');model.connect('achievements:helper','joinquit:style');
  assert.equal(model.item('contracts','order')['completion-achievement'],'helper');
  assert.equal(model.item('marks','mark').achievement,'helper');assert.equal(model.item('joinquit','style').achievement,'helper');
  assert.equal(contentLinks(model.catalogs).length,3);assert.deepEqual(contentErrors(model.catalogs),[]);
  assert.equal(new ContentModel(JSON.parse(JSON.stringify(model.body()))).item('marks','mark').achievement,'helper');
});
test('cycles and deleted referenced achievements remain visible as errors',async()=>{
  const {ContentModel,contentErrors}=await modulePromise;const model=new ContentModel(snapshot());
  model.add('achievements','a');model.add('achievements','b');model.connect('achievements:a','achievements:b');model.connect('achievements:b','achievements:a');
  assert.ok(contentErrors(model.catalogs).some(e=>e.includes('цикл')));
  model.remove('achievements','a');assert.ok(contentErrors(model.catalogs).some(e=>e.includes('achievements:a')));
});
test('stage reordering preserves data; duplication does not share mutable state',async()=>{
  const {ContentModel}=await modulePromise;const model=new ContentModel(snapshot());model.add('stories','story');
  model.item('stories','story').steps.push({type:'DELIVER_ITEM',description:'deliver',count:8});
  model.moveStep('stories','story',1,-1);assert.equal(model.item('stories','story').steps[0].type,'DELIVER_ITEM');
  model.add('stories','copy',model.item('stories','story'));model.set('stories','copy','steps.0.count',16);
  assert.equal(model.item('stories','story').steps[0].count,8);
});
test('NPC editing, prototype keys and replacing a metric trigger with a prerequisite are refused',async()=>{
  const {ContentModel}=await modulePromise;const model=new ContentModel(snapshot());
  assert.throws(()=>model.add('npcs','npc'));assert.throws(()=>model.add('marks','__proto__'));
  model.add('achievements','a');model.add('achievements','b');model.set('achievements','b','trigger',{type:'counter',key:'kills','at-least':10});
  assert.throws(()=>model.connect('achievements:a','achievements:b'));
  assert.throws(()=>model.set('achievements','b','__proto__.polluted',true));assert.equal({}.polluted,undefined);
});

test('preview content routes enforce permissions and shared draft conflicts',()=>{
  const {create}=require('../../main/resources/web/preview/preview-model.js');
  const model=create({clock:()=>1000});
  const live=model.request('GET','/api/content/catalogs').body.data;
  const saved=model.request('POST','/api/content/draft',live);
  assert.equal(saved.status,200);
  assert.equal(model.request('POST','/api/content/draft',live).status,409);
  assert.equal(model.request('POST','/api/content/publish',live).status,409);
  model.switchRole(2);
  assert.equal(model.request('GET','/api/content/catalogs').status,403);
  assert.equal(model.request('POST','/api/content/publish',saved.body.data).status,403);
});
