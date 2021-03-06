package org.hw.sml.support.ioc;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.hw.sml.FrameworkConstant;
import org.hw.sml.core.build.SmlTools;
import org.hw.sml.queryplugin.ArrayJsonMapper;
import org.hw.sml.support.ClassHelper;
import org.hw.sml.support.LoggerHelper;
import org.hw.sml.support.aop.AbstractAspect;
import org.hw.sml.support.aop.MethodProxyFactory;
import org.hw.sml.support.el.BeanType;
import org.hw.sml.support.el.ElContext;
import org.hw.sml.support.el.ElException;
import org.hw.sml.support.el.SmlElContext;
import org.hw.sml.support.ioc.annotation.Bean;
import org.hw.sml.support.ioc.annotation.Config;
import org.hw.sml.support.ioc.annotation.Config.Type;
import org.hw.sml.support.ioc.annotation.Init;
import org.hw.sml.support.ioc.annotation.Inject;
import org.hw.sml.support.ioc.annotation.Stop;
import org.hw.sml.support.ioc.annotation.Val;
import org.hw.sml.support.ioc.bean.AutoBean;
import org.hw.sml.support.ioc.bean.ConfigBean;
import org.hw.sml.support.time.SchedulerPanner;
import org.hw.sml.support.time.annotation.Scheduler;
import org.hw.sml.tools.Assert;
import org.hw.sml.tools.ClassUtil;
import org.hw.sml.tools.Https;
import org.hw.sml.tools.MapUtils;
import org.hw.sml.tools.RegexUtils;
import org.hw.sml.tools.Strings;


@SuppressWarnings({ "unchecked", "rawtypes" })
public class BeanHelper {
	private BeanHelper(){}
	public  static final  String IOC_BEAN_SCAN="ioc-bean-scan";
	public static final String INIT_BEAN_ELP="elp-init-";
	private static  Map<String,Object> beanMap=MapUtils.newLinkedHashMap();
	private static  Map<String,Object> propertyInitBeanMap=MapUtils.newLinkedHashMap();
	private static Map<String,Boolean> beanErrInfo=MapUtils.newLinkedHashMap();
	private static ElContext smlElContext=new SmlElContext();
	private static PropertiesHelper propertiesHelper=new PropertiesHelper();
	private static List<Class<?>> beanClasses=MapUtils.newArrayList();
	private static List<ConfigBean> configBeans=MapUtils.newArrayList();
	public static final String KEY_BEAN_PREFIX="bean-";
	static{
		try {
			String smlProfile=FrameworkConstant.getProfile();
			boolean smlProfileActive=SmlTools.isNotEmpty(smlProfile);
			propertiesHelper.withProperties(FrameworkConstant.otherProperties).renameValue(KEY_BEAN_PREFIX).renameValue(KEY_BEAN_PREFIX);
			smlElContext.withBeanMap(beanMap).withProperties(propertiesHelper.getValues()).init();
			registerBean("smlBeanHelper", new BeanHelper());
			registerBean("smlPropertiesHelper",propertiesHelper);
			String packageName=propertiesHelper.getsValue(IOC_BEAN_SCAN,"sml.ioc.scan");
			boolean isAnnotationScan=packageName!=null&&packageName.trim().length()>0;
			boolean isAopOpened=Boolean.valueOf(getValue("sml.aop.status"));
			if(isAnnotationScan){
				for(String pn:packageName.split(",| ")){
					List<Class<?>> cls=ClassHelper.getClassListByAnnotation(pn, Bean.class);
					for(Class<?> cl:cls){
						if(!cl.getName().startsWith(pn)){
							continue;
						}
						Bean bean=cl.getAnnotation(Bean.class);
						String profile=bean.profile();
						if(SmlTools.isNotEmpty(profile)&&((smlProfileActive&&!smlProfile.equals(profile))||!smlProfileActive) ){
							continue;
						}
						if(!beanClasses.contains(cl))
							beanClasses.add(cl);
					}
					List<Class<?>> configCls=ClassHelper.getClassListByAnnotation(pn, Config.class);
					for(Class<?> cl:configCls){
						if(!cl.getName().startsWith(pn)){
							continue;
						}
						Config config=cl.getAnnotation(Config.class);
						ConfigBean configBean=new ConfigBean(config,cl);
						if(!configBeans.contains(configBean))
							configBeans.add(configBean);
					}
				}
			}
			Collections.sort(configBeans);
			//LoggerHelper.getLogger().info(BeanHelper.class,configBeans.toString());
			//config 前置操作
			doConfig(configBeans,true);
			//对属性文件bean读取解析
			for(Map.Entry<String,String> entry:propertiesHelper.getValuesByKeyStart(KEY_BEAN_PREFIX).entrySet()){
				String beanNamet=entry.getKey().replaceFirst(KEY_BEAN_PREFIX,"");
				String beanName=beanNamet;
				String profile=null;
				if(beanName.endsWith(")")&&beanName.contains("(")){
					beanName=beanNamet.substring(0,beanNamet.indexOf("("));
					profile=RegexUtils.subString(beanNamet,"(",")");
				}
				Map<String,String> beanKeyValue=getBeanKeyValue(entry.getKey());
				String classpath=beanKeyValue.get("class");
				if(SmlTools.isNotEmpty(profile)&&((smlProfileActive&&!smlProfile.equals(profile))||!smlProfileActive) ){
					continue;
				}
				Assert.notNull(classpath,"bean["+beanName+"] class is null!");
				Assert.isTrue(!beanMap.containsKey(beanName),"bean["+beanName+"] name is conflict! ["+classpath+"]-["+(beanMap.get(beanName)!=null?beanMap.get(beanName).getClass().getName():"")+"]");
				Object bean=null;
				if(classpath.startsWith("[")&&classpath.endsWith("]")){
					classpath=classpath.substring(1,classpath.length()-1);
					bean=Array.newInstance(Class.forName(classpath),beanKeyValue.size()-1);
				}else if(classpath.endsWith(")")&&classpath.contains("(")){
					//通过构造初始化bean
					String clp=classpath.substring(0,classpath.indexOf("("));
					String clpBeanElp=classpath.substring(classpath.indexOf("(")+1,classpath.length()-1);
					String[] clpBeans=new Strings(clpBeanElp).splitToken(',','(',')');
					Object[] consts=new Object[clpBeans.length];
					Class<?>[] constCls=new Class<?>[clpBeans.length];
					for(int i=0;i<consts.length;i++){
						String keyP=clpBeans[i];
						BeanType b=smlElContext.evelBeanType(keyP);
						consts[i]=b.getV();
						constCls[i]=b.getC();
					}
					bean=ClassUtil.newInstance(Class.forName(clp), constCls, consts);
				}else{
					if(!Boolean.valueOf(beanKeyValue.get("passErr"))){
						bean=ClassUtil.newInstance(classpath);
					}else{
						try{
							bean=ClassUtil.newInstance(classpath);
						}catch(Exception e){
							e.printStackTrace();
							beanErrInfo.put(beanName,false);
						}
					}
				}
				registerBean(beanName,bean);
				propertyInitBeanMap.put(beanName,bean);
			}
			//查找所有Bean注解并生成对象
			if(isAnnotationScan){
				for(Class<?> clazz:beanClasses){
					Bean bean=clazz.getAnnotation(Bean.class);
					String profile=bean.profile();
					if(SmlTools.isNotEmpty(profile)&&((smlProfileActive&&!smlProfile.equals(profile))||!smlProfileActive) ){
						continue;
					}
					String beanName=bean.value();
					if(new Strings(bean.value()).isEmpty()){
						beanName=new Strings(clazz.getSimpleName()).toLowerCaseFirst();
					}
					registerBean(beanName,clazz.newInstance());
				}
			}
			if(isAopOpened){
				//aop切面编程-1找出切面bean
				List<AbstractAspect> aspects=MapUtils.newArrayList();
				for(Map.Entry<String,Object> beans:beanMap.entrySet()){
					Object bean=beans.getValue();
					if(bean instanceof AbstractAspect){
						String aopPropStart="aop.bean."+beans.getKey()+".";
						Map<String,String> bkv=propertiesHelper.getValuesByKeyStart(aopPropStart);
						Map<String,String> mapToBean=MapUtils.newHashMap();
						for(Map.Entry<String,String> entry:bkv.entrySet()){
							mapToBean.put(entry.getKey().replace(aopPropStart,""),entry.getValue());
						}
						ClassUtil.mapToBean(mapToBean,bean);
						//
						aspects.add((AbstractAspect)bean);
					}
				}
				//排序
				Collections.sort(aspects);
				for(Map.Entry<String,Object> beans:beanMap.entrySet()){
					Object bean=beans.getValue();
					if(!(bean instanceof AbstractAspect)){
						bean=MethodProxyFactory.newProxyInstance(bean, aspects.toArray(new AbstractAspect[]{}));
						beans.setValue(bean);
					}
				}
			}
			//初始化属性值
			for(Map.Entry<String,Object> entry:propertyInitBeanMap.entrySet()){
				String beanName=entry.getKey();
				Object bean=entry.getValue();
				if(beanErrInfo.containsKey(beanName)){
					continue;
				}
				//如果bean属于map类
				Map<String,String> pvs=getBeanKeyValue(beanName);
				int i=0;
				String autoConfigPrefix=null;
				for(Map.Entry<String,String> et:pvs.entrySet()){
					String k=et.getKey();
					if(k.startsWith("p-")){
						String[] ktoken=getPorM(k);
						String fieldName=ktoken[1];
						String fieldType=ktoken[2];
						if(bean instanceof Map){
								((Map) bean).put(fieldName,getValue(fieldType,et.getValue()));
						}else if(bean instanceof List){
								((List) bean).add(getValue(fieldType,et.getValue()));
						}else if(bean.getClass().isArray()){
								Array.set(bean, i++,ClassUtil.convertValueToRequiredType(getValue(fieldType,et.getValue()),bean.getClass().getComponentType()));
						}else{
							Field field=ClassUtil.getField(bean.getClass(),fieldName);
							if(fieldType!=null&&!fieldType.startsWith("m"))
							Assert.notNull(field, "bean["+beanName+"-"+bean.getClass()+"] has not field["+fieldName+"]");
							if(field!=null)
							field.setAccessible(true);
							if(fieldType==null||fieldType.equals("v")||fieldType.equals("b")){
								Assert.notNull(getValue(fieldType,et.getValue()),beanName+"-property["+et.getValue()+"] is not configed!");
								Assert.notNull(field, "bean["+beanName+"-"+bean.getClass()+"] has no field["+fieldName+"]");
								Object value=ClassUtil.convertValueToRequiredType(getValue(fieldType,et.getValue()), field.getType());
								Assert.notNull(value, "bean["+beanName+"-"+bean.getClass()+"] has no field "+fieldType+"["+et.getValue()+"]");
								field.set(bean,value.equals("")?null:value);
							}else if(fieldType.equals("m")||fieldType.equals("mv")||fieldType.equals("mb")){
								String methodName="set"+new Strings(fieldName).toUpperCaseFirst();
								Method method=ClassUtil.getMethod(bean.getClass(),methodName);
								method.setAccessible(true);
								Assert.notNull(method, "bean["+beanName+"-"+bean.getClass()+"] has not method["+methodName+"] for field["+fieldName+"]!");
								Object value=ClassUtil.convertValueToRequiredType(getValue(fieldType.replace("m",""),et.getValue()),method.getGenericParameterTypes()[0].getClass());
								Assert.notNull(value, "bean["+beanName+"-"+bean.getClass()+"] has not method["+methodName+"] for field "+fieldType+" params["+et.getValue()+"]!");
								method.invoke(bean,value.equals("")?null:value);
							}
							else
								field.set(bean,ClassUtil.convertValueToRequiredType(et.getValue(), field.getType()));
						}
					}else if(k.startsWith("m-")){
						String[] ktoken=getPorM(k);
						String methodName=ktoken[1];
						String methodType=ktoken[2];
						Method method=ClassUtil.getMethod(bean.getClass(),methodName);
						Assert.notNull(method, "bean["+beanName+"-"+bean.getClass()+"] has no method["+methodName+"]!");
						Object value=ClassUtil.convertValueToRequiredType(getValue(methodType,et.getValue()),method.getGenericParameterTypes()[0].getClass());
						Assert.notNull(value,"bean["+beanName+"-"+bean.getClass()+"] method ["+methodName+"] for params "+methodType+"["+et.getValue()+"]");
						method.invoke(bean, value.equals("")?null:value);
					}else if(k.equals("prefix")){
						autoConfigPrefix=pvs.get(k);
					}
				}
				if(SmlTools.isNotEmpty(autoConfigPrefix)){
					autoConfigProperties(autoConfigPrefix,bean);
				}
			}
			//注解类字段进行注入或赋值
			if(isAnnotationScan){
				//查询所有字段inject进行赋值
				for(Class<?> clazz:beanClasses){
					Bean bean=clazz.getAnnotation(Bean.class);
					String beanName=bean.value();
					if(new Strings(beanName).isEmpty())	beanName=new Strings(clazz.getSimpleName()).toLowerCaseFirst();
					//字段注入方式
					injectFieldBean(clazz,beanName,getBean(beanName),true,null);
					//方法注入方式
					injectMethodBean(clazz,beanName,getBean(beanName));
				}
				//@Val进行赋值
				for(Class<?> clazz:beanClasses){
					Bean bean=clazz.getAnnotation(Bean.class);
					String beanName=bean.value();
					if(beanName==null||beanName.trim().length()==0){
						beanName=new Strings(clazz.getSimpleName()).toLowerCaseFirst();
					}
					Object beanObj=getBean(beanName);
					//自动注入
					autoConfigProperties(bean.prefix(),beanObj);
					//val注入
					injectFieldVal(clazz, beanName,beanObj);
					//方法注入方式
					injectMethodVal(clazz, beanName,beanObj);
				}
			}
			//初始化属性文件配置中方法或注入关闭勾子
			for(Map.Entry<String,Object> entry:propertyInitBeanMap.entrySet()){
				String beanName=entry.getKey();
				if(beanErrInfo.containsKey(beanName)){
					continue;
				}
				final Object bean=entry.getValue();
				Map<String,String> pvs=getBeanKeyValue(beanName);
				for(final Map.Entry<String,String> et:pvs.entrySet()){
					String k=et.getKey();
					String methodName=et.getValue();
					if(k.equals("init-method")){
						final Method method=ClassUtil.getMethod(bean.getClass(),methodName);
						Assert.notNull(method, "bean["+beanName+"-"+bean.getClass()+"] has not init-method["+methodName+"]");
						method.setAccessible(true);
						boolean isDelay=Boolean.valueOf(pvs.get("isDelay"));
						LoggerHelper.getLogger().info(BeanHelper.class,"beanName["+beanName+"] init-method["+methodName+"] isDelay["+(isDelay?MapUtils.getString(pvs,"sleep","0")+"s":"false")+"]...");
						methodInvoke(bean, method, Boolean.valueOf(pvs.get("igErr")), isDelay,Long.parseLong(MapUtils.getString(pvs,"sleep","0")));
					}else if(k.equals("stop-method")||k.equals("destroy-method")){
						final Method method=ClassUtil.getMethod(bean.getClass(),methodName);
						Assert.notNull(method, "bean["+beanName+"-"+bean.getClass()+"] has not stop-method["+methodName+"]");
						method.setAccessible(true);
						Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
							public void run() {
								try {
									method.invoke(bean,new Object[]{});
								} catch (Exception e) {
									e.printStackTrace();
								}
							}
						}));
					}
				}
			}
			//初始化注解方法
			if(packageName!=null&&packageName.trim().length()>0){
				//@Init方法
				initAnnotationInvoke(beanClasses);
				//@Stop方法销毁
				for(Class<?> clazz:beanClasses){
					Bean bean=clazz.getAnnotation(Bean.class);
					 String beanName=bean.value();
					if(beanName==null||beanName.trim().length()==0){
						beanName=new Strings(clazz.getSimpleName()).toLowerCaseFirst();
					}
					final String tempBean=beanName;
					for(final Method method:ClassUtil.getMethods(clazz)){
						Stop stop=method.getAnnotation(Stop.class);
						if(stop!=null){
							method.setAccessible(true);
							Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
								public void run() {
									try {
										method.invoke(beanMap.get(tempBean), new Object[]{});
									} catch (Exception e) {
										e.printStackTrace();
									} 
								}
							}));
						}
					}
				}
			}
			doConfig(configBeans,false);
		} catch (Exception e) {
			e.printStackTrace();
			//System.exit(0);
			throw new RuntimeException(e);
		} 
		boolean externalSchedulerPanner=getBean(SchedulerPanner.class)==null;
		if(externalSchedulerPanner){
			SchedulerPanner schedulerPanner=new SchedulerPanner();
			schedulerPanner.setConsumerThreadSize(MapUtils.getInt(propertiesHelper.getValues(),"sml.server.scheduler.consumerThreadSize",2));
			schedulerPanner.setDepth(MapUtils.getInt(propertiesHelper.getValues(),"sml.server.scheduler.depth",10000));
			schedulerPanner.setTimeout(MapUtils.getInt(propertiesHelper.getValues(),"sml.server.scheduler.timeout",0));
			schedulerPanner.setSecondIntervals(MapUtils.getInt(propertiesHelper.getValues(),"sml.server.scheduler.secondIntervals",60));
			schedulerPanner.setSkipQueueCaseInExecute(MapUtils.getBoolean(propertiesHelper.getValues(),"sml.server.scheduler.skipQueueCaseInExecute",true));
			registerBean("schedulerPanner",schedulerPanner);
		}
		//扫描注解类任务调度
		SchedulerPanner schedulerPanner=getBean(SchedulerPanner.class);
		for(Map.Entry<String,Object> beans:beanMap.entrySet()){
			if(beanErrInfo.containsKey(beans.getKey())){
				continue;
			}
			for(Method method:ClassUtil.getMethods(beans.getValue().getClass())){
				Scheduler scheduler=method.getAnnotation(Scheduler.class);
				if(scheduler==null) continue;
				schedulerPanner.getTaskMapContain().put("anno-"+beans.getKey()+"."+method.getName(),MapUtils.getString(propertiesHelper.getValues(),scheduler.value(),scheduler.value()));
			}
		}
		if(externalSchedulerPanner)
		schedulerPanner.init();
		LoggerHelper.getLogger().info(BeanHelper.class,"bean initd--->"+beanMap.keySet());
		//执行      sml.initElp.=
		for(Map.Entry<String,String> entry:propertiesHelper.getValuesByKeyStart(INIT_BEAN_ELP).entrySet()){
			try {
				evelV(entry.getValue());
			} catch (Exception e) {
				LoggerHelper.getLogger().error(BeanHelper.class,String.format("elp init [%s] error:[%s]",entry.getKey(),e.getMessage()));
			}
		}
		//afterRegisterBeanInject
		afterRegisterBeanInject();
	}
	public static Object evelV(String elp) throws ElException{
		return smlElContext.evel(elp);
	}
	
	private static void afterRegisterBeanInject() {
		List<BeanInject> beanInjects=getBeans(BeanInject.class);
		for(BeanInject bi:beanInjects){
			for(Map.Entry<String,Object> entry:beanMap.entrySet()){
				bi.inject(entry.getKey(),entry.getValue());
			}
		}
	}
	public static void injectFieldVal(Class<?> clazz,String beanName,Object bean) throws ElException, IllegalArgumentException, IllegalAccessException{
		Field[] fields=ClassUtil.getFields(clazz);
		for(Field filed:fields){
			Val config=filed.getAnnotation(Val.class);
			if(config==null){
				continue;
			}
			String configName=config.value();
			Assert.notNull(configName, "beanName:"+beanName+"-"+bean.getClass()+",field config "+filed.getName()+" is null");
			filed.setAccessible(true);
			if(config.required())
			Assert.notNull(getValue(configName,config.isEvel()), "beanName:["+beanName+"-"+bean.getClass()+"],field value "+filed.getName()+" is null");
			if(getValue(configName,config.isEvel())!=null){
				filed.set(bean,ClassUtil.convertValueToRequiredType(getValue(configName,config.isEvel()),filed.getType()));
			}
		}
	}
	public static void injectMethodVal(Class<?> clazz,String beanName,Object beanObj) throws ElException, IllegalArgumentException, IllegalAccessException, InvocationTargetException{
		Method[] methods=ClassUtil.getMethods(clazz);
		for(Method method:methods){
			Val val=method.getAnnotation(Val.class);
			if(val==null){
				continue;
			}
			String configName=val.value();
			method.setAccessible(true);
			Assert.notNull(getValue(configName,val.isEvel()), "beanName:["+beanName+"-"+beanObj.getClass()+"],method param "+method.getName()+" is null");
			method.invoke(beanObj,ClassUtil.convertValueToRequiredType(getValue(configName,val.isEvel()),method.getParameterTypes()[0]));
		}
	}
	public static void injectFieldBean(Class<?> clazz,String beanName,Object bean,boolean checkExists,String fieldname) throws IllegalArgumentException, IllegalAccessException{
		Field[] fields=ClassUtil.getFields(clazz);
		for(Field filed:fields){
			Inject inject=filed.getAnnotation(Inject.class);
			if(inject==null){
				continue;
			};
			if(fieldname!=null&&!(filed.getName().equals(fieldname)||fieldname.equals(inject.value()))){
				continue;
			}
			String injectName=inject.value();
			Strings injectStrings=new Strings(injectName);
			if(injectStrings.isEmpty())	injectName=new Strings(filed.getType().getSimpleName()).toLowerCaseFirst();
			filed.setAccessible(true);
			Object v= getBean(injectName)==null?getBean(filed.getName()):getBean(injectName);
			if(injectStrings.isEmpty()&&v==null&&!inject.injectByName()){
				v=getBean(filed.getType());
			}
			if(checkExists&&inject.required())
				Assert.notNull(v, "beanName:["+beanName+"-"+bean.getClass()+"],field inject ["+filed.getName()+"] v is null");
			if(v!=null){
				filed.setAccessible(true);
				filed.set(bean,v.equals("")?null:v);
			}
			
		}
	}
	public static void injectMethodBean(Class<?> clazz,String beanName,Object bean) throws Exception{
		Method[] methods=ClassUtil.getMethods(clazz);
		for(Method method:methods){
			Inject inject=method.getAnnotation(Inject.class);
			if(inject==null){
				continue;
			}
			String injectName=inject.value();
			Strings injectStrings=new Strings(injectName);
			if(injectStrings.isEmpty()){
				try{
					injectName=new Strings(method.getParameterTypes()[0].getSimpleName()).toLowerCaseFirst();
				}catch(Exception e){
					LoggerHelper.getLogger().error(BeanHelper.class,"bean["+beanName+"] inject method "+method.getName()+" error ["+e.getMessage()+"]");
					throw e;
				}
			}
			method.setAccessible(true);
			Object v=getBean(injectName)==null?getBean(method.getParameterTypes()[0]):getBean(injectName);
			Assert.notNull(v, "beanName:["+beanName+"-"+bean.getClass()+"],method inject ["+method.getName()+" params ] v is null");
			method.invoke(getBean(beanName),v);
		}
	}

	public static void initAnnotationInvoke(List<Class<?>> classes) throws Exception{
		//@Init方法
		for(Class<?> clazz:classes){
			Bean bean=clazz.getAnnotation(Bean.class);
			String beanName=bean.value();
			if(beanName==null||beanName.trim().length()==0){
				beanName=new Strings(clazz.getSimpleName()).toLowerCaseFirst();
			}
			Method[] ms=ClassUtil.getMethods(clazz);
			List<String> initdMethod=MapUtils.newArrayList();
			for(Method method:ms){
				Init init=method.getAnnotation(Init.class);
				if(init!=null){
					method.setAccessible(true);
					if(initdMethod.contains(method.getName())){
						continue;
					}
					initdMethod.add(method.getName());
					methodInvoke(beanMap.get(beanName), method, init.igErr(), init.isDelay(), init.sleep());
				}
			}
		}
	}
	private static void doConfig(List<ConfigBean> configBeans,boolean pre) throws IllegalArgumentException, ElException, IllegalAccessException, SecurityException, InvocationTargetException, NoSuchMethodException{
		for(ConfigBean configBean:configBeans){
				if(!configBean.getConfig().value().equals(pre?Type.before:Type.after)){
					continue;
				}
				if(     (SmlTools.isEmpty(configBean.getConfig().conditionalOnExistsVals())||propertiesHelper.exists(configBean.getConfig().conditionalOnExistsVals()))&&
						(SmlTools.isEmpty(configBean.getConfig().conditionOnMatchVals())||propertiesHelper.isTrueVal(configBean.getConfig().conditionOnMatchVals()))&&
						(SmlTools.isEmpty(configBean.getConfig().conditionOnMatchValMissingTrue())||propertiesHelper.isTrueValMissingTrue(configBean.getConfig().conditionOnMatchValMissingTrue()))&&
						(SmlTools.isEmpty(configBean.getConfig().conditionalOnExistsBeans())||existsBeans(configBean.getConfig().conditionalOnExistsBeans()))&&
						(SmlTools.isEmpty(configBean.getConfig().conditionOnExistsPkgs())||ClassUtil.existsPkgs(configBean.getConfig().conditionOnExistsPkgs()))
						)
						{
					LoggerHelper.getLogger().info(BeanHelper.class,configBean.toString());
				}else{
					LoggerHelper.getLogger().warn(BeanHelper.class,configBean+" is not conditional ,then ignore!");
					continue;
				}
				injectFieldVal(configBean.getClazz(),configBean.getClazz().getSimpleName(),configBean.getBean());
				autoConfigProperties(configBean.getConfig().prefix(),configBean.getBean());
				injectFieldBean(configBean.getClazz(),configBean.getClazz().getSimpleName(),configBean.getBean(),false,null);
				Method[] methods=ClassUtil.getMethods(configBean.getClazz());
				List<AutoBean> autoBeans=MapUtils.newArrayList();
				for(Method method:methods){
					Bean bean=method.getAnnotation(Bean.class);
					if(bean==null){
						continue;
					}
					if((SmlTools.isEmpty(bean.conditionalOnExistsVals())||propertiesHelper.exists(bean.conditionalOnExistsVals()))&&
							(SmlTools.isEmpty(bean.conditionOnMatchVals())||propertiesHelper.isTrueVal(bean.conditionOnMatchVals()))&&
							(SmlTools.isEmpty(bean.conditionOnMatchValMissingTrue())||propertiesHelper.isTrueValMissingTrue(bean.conditionOnMatchValMissingTrue()))&&
							(SmlTools.isEmpty(bean.conditionalOnExistsBeans())||existsBeans(bean.conditionalOnExistsBeans()))&&
							(SmlTools.isEmpty(bean.conditionOnExistsPkgs())||ClassUtil.existsPkgs(bean.conditionOnExistsPkgs()))){
						autoBeans.add(new AutoBean(method.getName(),bean));
					}
				}
				Collections.sort(autoBeans);
				for(AutoBean autoBean:autoBeans){
					if(beanMap.containsKey(autoBean.getBeanName())&&autoBean.getBean().conditionalOnMissingBean()){
						continue;
					}
					Object bean=ClassUtil.invokeMethod(configBean.getBean(),autoBean.getMethod(),null,null);
					registerBean(autoBean.getBeanName(),bean);
					autoConfigProperties(autoBean.getBean().prefix(),getBean(autoBean.getBeanName()));
					if(SmlTools.isNotEmpty(autoBean.getBean().initMethod())){
						ClassUtil.invokeMethod(bean,autoBean.getBean().initMethod(),null,null);
					}
					injectFieldBean(configBean.getBean().getClass(),configBean.getBean().getClass().getSimpleName(),configBean.getBean(),false,autoBean.getBeanName());
					if(SmlTools.isNotEmpty(autoBean.getBean().destoryMethod())){
						
					}
				}
		}
	}

	public static boolean existsBeans(String[] conditionalOnExistsBeans) {
		for(String ceb:conditionalOnExistsBeans){
			if(!beanMap.containsKey(ceb)){
				return false;
			}
		}
		return true;
	}

	public static void autoConfigProperties(String prefix,Object beanObj){
		if(SmlTools.isEmpty(prefix)){
			return;
		}
		Field[] fields=ClassUtil.getFields(beanObj.getClass());
		for(Field field:fields){
			Val val=field.getAnnotation(Val.class);
			Inject inj=field.getAnnotation(Inject.class);
			Map<String,Object> values=propertiesHelper.getValuesWithoutPrifix(prefix);
			if(val==null&&inj==null){
				if(SmlTools.isNotEmpty(values.get(field.getName()))){
					field.setAccessible(true);
					try{
						field.set(beanObj,ClassUtil.convertValueToRequiredType(values.get(field.getName()),field.getType()));
					}catch(Exception e){
						LoggerHelper.getLogger().error(BeanHelper.class,"auto config field["+field.getName()+"] error:["+e.getMessage()+"]");
					}
				}
			}
		}
	}
	public static int start(){
		return start(new String[]{});
	}
	public static int start(String[] args){
		return 0;
	}
	public static <T> T getBean(String name){
		T t=(T) beanMap.get(name);
		if(t==null){
			List<BeanFactory> bfs=getBeans(BeanFactory.class);
			for(BeanFactory bf:bfs){
				t=(T) bf.getBean(name);
				if(t!=null){
					beanMap.put(name,t);
					return t; 
				}
			}
		}
		return t;
	}
	public static <T> T getBean(Class<T> t){
		for(Map.Entry<String,Object> entry:beanMap.entrySet()){
			Object val=entry.getValue();
			if(val.getClass().equals(t))
				return (T)val;
			if(t.isInstance(val))
				return (T)val;
		}
		return null;
	}
	public static <T> List<T> getBeans(Class<T> t){
		List<T> result=MapUtils.newArrayList();
		for(Map.Entry<String,Object> entry:beanMap.entrySet()){
			Object val=entry.getValue();
			if(val.getClass().equals(t))
				result.add((T)val);
			else if(t.isInstance(val))
				result.add((T)val);
		}
		return result;
	}
	public static String getValue(String key){
		if(key.startsWith("${")&&key.endsWith("}")){
			key=key.substring(2,key.length()-1);
		}
		String result= propertiesHelper.getValue(key);
		if(result==null){
			for(ValueFactory vf:getBeans(ValueFactory.class)){
				result=vf.getValue(key);
				if(result!=null){
					propertiesHelper.getValues().put(key,result);
					return result;
				}
			}
		}
		return result;
	}
	public static String getsValue(String ... keys){
		String value=null;
		for(String key:keys){
			value=getValue(key);
			if(value!=null){
				break;
			}
		}
		return value;
	}
	public static Object getValue(String key,boolean isEvel) throws ElException{
		if(!isEvel)
			return getValue(key);
		else{
			String value=getValue(key);
			value=value==null?key:value;
			return evelV(value);
		}
	}
	public static Object getValue(String type,String key) throws IllegalArgumentException, IllegalAccessException, ElException{
		if(type==null){
			
		}else if(type.equals("v")){
			return getValue(key);
		}else if(type.equals("b")){
			if(!beanErrInfo.containsKey(key))
				return smlElContext.getBean(key);
			else
				return "";
		}
		if(key.startsWith("${")&&key.endsWith("}")){
			return evelV(key);
		}else if(key.startsWith("#{")&&key.endsWith("}")){
			String keyElp=key.substring(2,key.length()-1);
			 if(!beanErrInfo.containsKey(keyElp))
				return smlElContext.evel(key);
			 else
			    return "";
		}
		return smlElContext.evel(key);
	}
	public static Map<String,String> getBeanKeyValue(String key){
		if(!key.startsWith(KEY_BEAN_PREFIX)){
			key=KEY_BEAN_PREFIX+key;
		}
		String value=getValue(key);
		Assert.notNull(value,key+" not found!");
		return MapUtils.transMapFromStr(value);
	}
	public static Map<String,Object> getBeanMap(){
		return beanMap;
	}
	public static void registerBean(String alias,Object bean){
		beanMap.put(alias,bean);
		if(bean instanceof ArrayJsonMapper){
			Https.bindJsonMapper((ArrayJsonMapper)bean);
		}
		List<BeanListener> beanListeners=BeanHelper.getBeans(BeanListener.class);
		for(BeanListener bl:beanListeners){
			bl.listener(alias,bean);
		}
	}
	private static String[] getPorM(String key){
		String[] pms= key.split("-");
		return new String[]{pms[0],pms[1],pms.length==3?pms[2]:null};
	}
	private static void methodInvoke(final Object bean,final Method method,boolean igErr,boolean isDelay,final long ms) throws Exception{
		if(isDelay){
			Thread thread=new Thread(new Runnable(){
				public void run() {
					try {
						Thread.sleep(ms*1000);
						method.invoke(bean,new Object[]{});
					} catch (Exception e) {
						e.printStackTrace();
					} 
				}});
			thread.setName(bean.getClass().getSimpleName()+"."+method.getName());
			thread.start();
			LoggerHelper.getLogger().info(BeanHelper.class,"bean["+bean.getClass()+"]"+method.getName()+" lazy load sleep "+ms+" s!");
		}else{
			if(igErr){
				try {
					method.invoke(bean,new Object[]{});
				} catch (Exception e) {
					e.printStackTrace();
				} 
			}else{
				method.invoke(bean,new Object[]{});
			}
		}
	}
	public static List<Class<?>> getBeanDefineClasses(){
		return beanClasses;
	}
	public static List<ConfigBean> getConfigBeans(){
		return configBeans;
	}
	public static void main(String[] args) {
		BeanHelper.start(args);
	}
}
