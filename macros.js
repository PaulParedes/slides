remark.macros.scale = function (percentage) {
  var url = this;
  return '<img src="' + url + '" style="width: ' + percentage + '" />';
};
var macros = module.exports = {};

macros.hello = function () {
  return 'hello!';
};