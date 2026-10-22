-- 修正旧库千帆 remark：两种历史文案都覆盖；只动出厂原文，用户改过的不碰。
UPDATE ai_engine
   SET remark = '需先在千帆控制台开通在线服务；无零价型号，赠送额度型按量计费'
 WHERE id = 'qianfan-ernie-speed'
   AND remark = '需先在千帆控制台开通在线服务；实测当前账号无免费型号';
UPDATE ai_engine
   SET remark = '需先在千帆控制台开通在线服务；无零价型号，赠送额度型按量计费'
 WHERE id = 'qianfan-ernie-speed'
   AND remark = '需先在千帆控制台开通在线服务；ERNIE-Speed 已不在清单，只能用付费型号';
