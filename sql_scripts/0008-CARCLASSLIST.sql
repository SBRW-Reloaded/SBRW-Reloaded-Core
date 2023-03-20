CREATE TABLE IF NOT EXISTS `CAR_CLASSLIST` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `hash` int(11) DEFAULT NULL,
  `name` varchar(50) DEFAULT NULL,
  `minVal` int(11) DEFAULT NULL,
  `maxVal` int(11) DEFAULT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4;

INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (1, 869393278, 'CLASS F', 0, 49);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (2, 872416321, 'CLASS E', 50, 249);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (3, 415909161, 'CLASS D', 250, 399);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (4, 1866825865, 'CLASS C', 400, 499);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (5, -406473455, 'CLASS B', 500, 599);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (6, -405837480, 'CLASS A', 600, 749);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (7, -2142411446, 'CLASS S', 750, 999);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (8, 86241155, 'CLASS S1', 1000, 1249);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (9, 221915816, 'CLASS S2', 1250, 1499);
INSERT INTO `CAR_CLASSLIST` (`id`, `hash`, `name`, `minVal`, `maxVal`) VALUES (10, 1526233495, 'CLASS S3', 1500, 1999);