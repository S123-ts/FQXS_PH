-- 旧库实际文案没有「先」字（V9 的条件多了一个字，没命中）。
-- 用 LIKE 只锚定「ERNIE-Speed 已不在清单」这段出厂措辞，避免再被全角/空格差异绊住。
UPDATE ai_engine
   SET remark = '需先在千帆控制台开通在线服务；无零价型号，赠送额度型按量计费'
 WHERE id = 'qianfan-ernie-speed'
   AND remark LIKE '%ERNIE-Speed 已不在清单%';
