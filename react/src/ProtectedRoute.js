import React from 'react';
import { Route, Redirect } from 'react-router-dom';

const PrivateRoute = ({ children, ...rest }) => {
  const isAuthenticated = localStorage.getItem('id');
  return (
    <Route {...rest}>
      {isAuthenticated === null ? <Redirect to='/' /> : children}
    </Route>
  );
};

export default PrivateRoute;
